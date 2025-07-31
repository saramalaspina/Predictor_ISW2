package controller;

import model.EvaluationResult;
import model.JavaMethod;
import model.Release;
import model.WekaClassifier;
import utils.PrintUtils;
import utils.WekaUtils;
import weka.classifiers.Evaluation;
import weka.core.Instances;
import weka.core.converters.ArffSaver;
import weka.core.converters.ConverterUtils.DataSource;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;


public class WekaAnalysis {

    private static final Logger LOGGER = Logger.getLogger(WekaAnalysis.class.getName());

    private final String project;
    private final List<JavaMethod> allMethods;
    private final List<Release> releasesToAnalyze;
    private final List<EvaluationResult> evaluationResults;
    private List<String> currentTestingMethodNames;

    public WekaAnalysis(String project, List<JavaMethod> allMethods, List<Release> releasesToAnalyze) {
        this.project = project;
        this.allMethods = allMethods;
        this.releasesToAnalyze = releasesToAnalyze;
        this.evaluationResults = new ArrayList<>();
    }


    public void execute() {
        LOGGER.log(Level.INFO, "--- Starting WEKA analysis for project: {0} ---", project);
        try {
            // Prepare the necessary data files (training/testing sets for each iteration)
            prepareWalkForwardData();

            // Run the classification experiments
            runClassification();

            // Save the final results to a CSV file
            saveResults();

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "An error occurred during WEKA analysis", e);
        }
        LOGGER.log(Level.INFO, "--- WEKA analysis finished for project: {0} ---", project);
    }

    // For each iteration, it creates a training set with releases 1 to N, and a testing set with release N+1.
    private void prepareWalkForwardData() throws IOException {
        LOGGER.info("Preparing data for walk-forward validation...");

        // The loop goes up to releasesToAnalyze.size() - 1 because the last release is only used for testing
        for (int i = 1; i < releasesToAnalyze.size(); i++) {
            final int trainingReleaseId = i; // Release ID for the end of the training period
            final int testingReleaseId = i + 1;


            List<JavaMethod> trainingMethods = allMethods.stream()
                    .filter(m -> m.getRelease().getId() == trainingReleaseId)
                    .collect(Collectors.toList());

            List<JavaMethod> testingMethods = allMethods.stream()
                    .filter(m -> m.getRelease().getId() == testingReleaseId)
                    .collect(Collectors.toList());

            if (testingMethods.isEmpty()) {
                LOGGER.log(Level.WARNING, "Skipping iteration {0}: No methods in testing release {1}", new Object[]{i, trainingReleaseId + 1});
                continue;
            }


            String iterDir = "arffFiles/" + project + "/iteration_" + i;
            new File(iterDir).mkdirs();

            // 1. Build training Instances in memory
            String trainingRelationName = project + "_Training_" + i;
            Instances trainingSet = WekaUtils.buildInstances(trainingMethods, trainingRelationName);

            // 2. Save the training Instances directly to an ARFF file
            ArffSaver saver = new ArffSaver();
            saver.setInstances(trainingSet);
            saver.setFile(new File(iterDir + "/Training.arff"));
            saver.writeBatch();

            // 3. Build testing Instances in memory
            String testingRelationName = project + "_Testing_" + i;
            Instances testingSet = WekaUtils.buildInstances(testingMethods, testingRelationName);

            // 4. Save the testing Instances directly to an ARFF file
            saver.setInstances(testingSet);
            saver.setFile(new File(iterDir + "/Testing.arff"));
            saver.writeBatch();

            }
        LOGGER.info("Walk-forward data preparation complete.");
    }


    private void runClassification() throws Exception {
        LOGGER.info("Starting classification experiments...");

        for (int i = 1; i < releasesToAnalyze.size(); i++) {
            // --- STEP 1: PREPARE DATA AND IDS FOR THE CURRENT ITERATION ---

            // Get the list of methods and their names for the current testing set
            final int testingReleaseId = i + 1;
            List<JavaMethod> testingMethods = allMethods.stream()
                    .filter(m -> m.getRelease().getId() == testingReleaseId)
                    .collect(Collectors.toList());

            // If there are no methods in this testing release, skip the entire iteration.
            if (testingMethods.isEmpty()) {
                LOGGER.log(Level.WARNING, "Skipping iteration {0}: No methods found for testing release {1}", new Object[]{i, testingReleaseId});
                continue;
            }

            // Store the names of the methods in the exact order they will appear in the dataset.
            List<String> currentTestingMethodNames = testingMethods.stream()
                    .map(JavaMethod::getFullyQualifiedName)
                    .collect(Collectors.toList());

            // --- STEP 2: LOAD WEKA DATASETS ---

            String trainingArffPath = "arffFiles/" + project.toLowerCase() + "/iteration_" + i + "/Training.arff";
            String testingArffPath = "arffFiles/" + project.toLowerCase() + "/iteration_" + i + "/Testing.arff";

            if (!new File(trainingArffPath).exists() || !new File(testingArffPath).exists()) {
                LOGGER.log(Level.WARNING, "Skipping iteration {0}: ARFF files not found.", i);
                continue;
            }

            DataSource trainingSource = new DataSource(trainingArffPath);
            Instances trainingSet = trainingSource.getDataSet();

            DataSource testingSource = new DataSource(testingArffPath);
            Instances testingSet = testingSource.getDataSet();

            // --- STEP 3: SANITY CHECK ---
            // Ensure that the number of instances in the loaded dataset matches the number of method names we have.
            if (testingSet.numInstances() != currentTestingMethodNames.size()) {
                LOGGER.log(Level.SEVERE, "FATAL: Mismatch between number of instances in Testing.arff ({0}) and number of method names ({1}) for iteration {2}. Aborting.",
                        new Object[]{testingSet.numInstances(), currentTestingMethodNames.size(), i});
                return; // Stop the analysis if there's a data integrity issue.
            }

            // Set the class attribute (the last one)
            trainingSet.setClassIndex(trainingSet.numAttributes() - 1);
            testingSet.setClassIndex(testingSet.numAttributes() - 1);

            // --- STEP 4: RUN CLASSIFICATION EXPERIMENTS ---

            List<WekaClassifier> classifiersToTest = ClassifierBuilder.buildClassifiers(trainingSet);
            LOGGER.log(Level.INFO, "--- Iteration {0}: Training on {1} instances, Testing on {2} instances ---", new Object[]{i, trainingSet.numInstances(), testingSet.numInstances()});

            for (WekaClassifier wekaClf : classifiersToTest) {
                wekaClf.getClassifier().buildClassifier(trainingSet);

                Evaluation eval = new Evaluation(testingSet);
                // This call both evaluates and returns individual predictions if needed later
                eval.evaluateModel(wekaClf.getClassifier(), testingSet);

                int buggyClassIndex = trainingSet.classAttribute().indexOfValue("yes");
                if (buggyClassIndex == -1) {
                    LOGGER.severe("Buggy class 'yes' not found. Check your data.");
                    return;
                }

                // Collect and store aggregated evaluation metrics
                EvaluationResult result = new EvaluationResult(
                        project, i, wekaClf.getName(),
                        wekaClf.getFeatureSelection(), wekaClf.getSampling(), wekaClf.getCostSensitive(),
                        eval.precision(buggyClassIndex), eval.recall(buggyClassIndex),
                        eval.areaUnderROC(buggyClassIndex), eval.kappa()
                );
                this.evaluationResults.add(result);
            }
        }
        LOGGER.info("Classification experiments complete.");
    }


    private void saveResults() throws IOException {
        LOGGER.info("Saving evaluation results...");
        // Use your utility class to write the results
        PrintUtils.printEvaluationResults(project, this.evaluationResults);
        LOGGER.info("Evaluation results saved successfully.");
    }
}