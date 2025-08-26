package controller;

import model.EvaluationResult;
import model.Metrics;
import model.PredictionResult;
import model.WekaClassifier;
import utils.AcumeUtils;
import utils.PrintUtils;
import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.core.Attribute;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.ArffSaver;
import weka.core.converters.CSVLoader;
import weka.core.converters.ConverterUtils.DataSource;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.Remove;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;

public class WekaAnalysis {

    private static final Logger LOGGER = Logger.getLogger(WekaAnalysis.class.getName());

    private final String project;
    private final Instances fullDataset;
    private final List<EvaluationResult> walkForwardResults;
    private final List<EvaluationResult> crossValResults;

    public WekaAnalysis(String project) throws Exception {
        this.project = project;
        this.walkForwardResults = new ArrayList<>();
        this.crossValResults = new ArrayList<>();

        String datasetCsvPath = String.format("reportFiles/%s/Dataset.csv", project.toLowerCase());
        LOGGER.log(Level.INFO, "Loading full dataset from CSV: {0} ", datasetCsvPath);

        CSVLoader loader = new CSVLoader();
        loader.setSource(new File(datasetCsvPath));
        Instances rawData = loader.getDataSet();

        Remove removeFilter = new Remove();
        removeFilter.setAttributeIndices("1"); // Remove FullyQualifiedName
        removeFilter.setInputFormat(rawData);
        this.fullDataset = Filter.useFilter(rawData, removeFilter);

        if (this.fullDataset.classIndex() == -1) {
            this.fullDataset.setClassIndex(this.fullDataset.numAttributes() - 1);
        }
        LOGGER.log(Level.INFO, "Dataset for {0} loaded successfully with {1} instances and {2} attributes.", new Object[]{project, this.fullDataset.numInstances(), this.fullDataset.numAttributes()});
    }

    // --- WALK-FORWARD ANALYSIS (con ARFF) ---

    public void executeWalkForward() {
        LOGGER.log(Level.INFO, "--- Starting WALK-FORWARD analysis for project: {0} ---", project);
        try {
            prepareWalkForwardArffs();
            runWalkForwardFromArffs();
            saveResults("walkForward", this.walkForwardResults);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "An error occurred during Walk-Forward analysis", e);
        }
        LOGGER.log(Level.INFO, "--- Walk-Forward analysis finished for project: {0} ---", project);
    }


    private void prepareWalkForwardArffs() throws IOException {
        LOGGER.info("Preparing ARFF files for walk-forward validation...");
        Attribute releaseAttr = this.fullDataset.attribute("Release");
        if (releaseAttr == null) {
            throw new IllegalStateException("Dataset must have a 'Release' attribute.");
        }

        int releaseIndex = releaseAttr.index();
        int maxRelease = (int) IntStream.range(0, fullDataset.numInstances())
                .mapToDouble(i -> fullDataset.instance(i).value(releaseIndex))
                .max().orElse(0);

        ArffSaver saver = new ArffSaver();
        for (int i = 1; i < maxRelease; i++) {
            Instances trainingSet = filterInstancesByRelease(this.fullDataset, releaseIndex, i, true);
            Instances testingSet = filterInstancesByRelease(this.fullDataset, releaseIndex, i + 1, false);

            if (!testingSet.isEmpty()) {
                String iterDir = String.format("arffFiles/%s/walkForward/iteration_%d", project.toLowerCase(), i);
                new File(iterDir).mkdirs();

                saver.setInstances(trainingSet);
                saver.setFile(new File(iterDir + "/Training.arff"));
                saver.writeBatch();

                saver.setInstances(testingSet);
                saver.setFile(new File(iterDir + "/Testing.arff"));
                saver.writeBatch();
            }
        }
        LOGGER.info("Walk-forward ARFF files preparation complete.");
    }

    private void runWalkForwardFromArffs() throws Exception {
        LOGGER.info("Starting PARALLELIZED walk-forward classification from ARFF files...");
        String acumeOutputDir = String.format("acumeFiles/%s/input/walkForward/", this.project.toLowerCase());
        new File(acumeOutputDir).mkdirs();

        int maxRelease = (int) this.fullDataset.attributeStats(this.fullDataset.attribute("Release").index()).numericStats.max;

        for (int i = 1; i < maxRelease; i++) {
            String trainingPath = String.format("arffFiles/%s/walkForward/iteration_%d/Training.arff", project.toLowerCase(), i);
            String testingPath = String.format("arffFiles/%s/walkForward/iteration_%d/Testing.arff", project.toLowerCase(), i);

            if (!new File(trainingPath).exists() || !new File(testingPath).exists()) continue;

            DataSource trainingSource = new DataSource(trainingPath);
            Instances trainingSet = trainingSource.getDataSet();
            trainingSet.setClassIndex(trainingSet.numAttributes() - 1);

            DataSource testingSource = new DataSource(testingPath);
            Instances testingSet = testingSource.getDataSet();
            testingSet.setClassIndex(testingSet.numAttributes() - 1);

            if (testingSet.isEmpty()) continue;

            LOGGER.log(Level.INFO, "--- WF Iteration {0}: Training on {1}, Testing on {2} ---", new Object[]{i, trainingSet.numInstances(), testingSet.numInstances()});

            List<WekaClassifier> classifiersToTest = ClassifierBuilder.buildClassifiers(trainingSet);
            int buggyClassIndex = trainingSet.classAttribute().indexOfValue("yes");
            final int iterationId = i;

            classifiersToTest.parallelStream().forEach(config -> {
                try {
                    LOGGER.log(Level.INFO, "Processing classifier {0} for WF iteration {1} on thread {2}", new Object[]{config.getDescriptiveName(), iterationId, Thread.currentThread().getName()});

                    WekaClassifier freshWekaClassifier = ClassifierBuilder.buildSpecificClassifier(
                            config.getName(), config.getSampling(), config.getFeatureSelection(), config.getCostSensitive(), trainingSet
                    );
                    Classifier classifierInstance = freshWekaClassifier.getClassifier();

                    classifierInstance.buildClassifier(trainingSet);

                    List<PredictionResult> predictionResults = getPredictionResults(classifierInstance, testingSet);

                    String fileSuffix = "wf_iter" + iterationId + ".csv";
                    String descriptiveFileName = config.getDescriptiveName().replaceAll("\\s+", "") + "_" + fileSuffix;
                    String acumeOutputFile = acumeOutputDir + project.toLowerCase() + "_" + descriptiveFileName;
                    AcumeUtils.exportToAcumeCsv(acumeOutputFile, predictionResults);

                    Evaluation eval = new Evaluation(trainingSet);
                    eval.evaluateModel(classifierInstance, testingSet);

                    Metrics metrics = new Metrics(eval.precision(buggyClassIndex), eval.recall(buggyClassIndex), eval.areaUnderROC(buggyClassIndex), eval.kappa(), eval.fMeasure(buggyClassIndex));
                    EvaluationResult result = new EvaluationResult(project, iterationId, config, metrics);

                    synchronized (this.walkForwardResults) {
                        this.walkForwardResults.add(result);
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Error processing classifier in Walk-Forward iteration {0}: {1}", new Object[]{iterationId, e});
                }
            });
        }
    }

// --- CROSS-VALIDATION ANALYSIS ---

    public void executeCrossValidation(int numRuns, int numFolds) {
        LOGGER.log(Level.INFO,"Cross Validation {0} Times {1} Folds", new Object[]{numRuns, numFolds} );
        LOGGER.log(Level.INFO, "--- Starting FOLD analysis for project: {0} ---", project);
        try {
            if (this.fullDataset.isEmpty()) {
                LOGGER.severe("Dataset is empty, aborting cross-validation.");
                return;
            }
            runCrossValidationClassification(numRuns, numFolds);
            saveResults("crossValidation", this.crossValResults);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "An error occurred during FOLD analysis", e);
        }
        LOGGER.log(Level.INFO, "--- FOLD analysis finished for project: {0} ---", project);
    }

    // Cross Validation Parallela
    private void runCrossValidationClassification(int numRuns, int numFolds) {
        LOGGER.info("Starting PARALLELIZED cross-validation classification and ACUME file generation...");
        String acumeOutputDir = String.format("acumeFiles/%s/crossValidation/", this.project.toLowerCase());
        new File(acumeOutputDir).mkdirs();

        List<WekaClassifier> classifierConfigurations = ClassifierBuilder.buildClassifiers(this.fullDataset);

        classifierConfigurations.parallelStream().forEach(config -> {
            try {
                LOGGER.log(Level.INFO, "==========================================================");
                LOGGER.log(Level.INFO, "Processing Classifier Config: {0} on thread {1}", new Object[]{config.getDescriptiveName(), Thread.currentThread().getName()});
                LOGGER.log(Level.INFO, "==========================================================");

                for (int run = 1; run <= numRuns; run++) {
                    LOGGER.log(Level.INFO, "--- Starting Run {0}/{1} for config {2} ---", new Object[]{run, numRuns, config.getDescriptiveName()});

                    List<PredictionResult> aggregatedPredictionsForRun = new ArrayList<>();
                    List<Evaluation> evaluationsForRun = new ArrayList<>();

                    Random rand = new Random(run);
                    Instances randData = new Instances(this.fullDataset);
                    randData.randomize(rand);
                    if (randData.classAttribute().isNominal()) {
                        randData.stratify(numFolds);
                    }

                    for (int fold = 0; fold < numFolds; fold++) {
                        LOGGER.log(Level.FINE, "Processing fold {0}/{1} for {2}...", new Object[]{fold + 1, numFolds, config.getDescriptiveName()});

                        Instances trainingSet = randData.trainCV(numFolds, fold, rand);
                        Instances testingSet = randData.testCV(numFolds, fold);
                        if (testingSet.isEmpty()) continue;

                        // Usiamo il builder per creare un'istanza nuova e pulita.
                        // Questo garantisce la thread-safety indipendentemente dalla versione di Weka.
                        WekaClassifier freshWekaClassifier = ClassifierBuilder.buildSpecificClassifier(
                                config.getName(), config.getSampling(), config.getFeatureSelection(), config.getCostSensitive(), trainingSet
                        );
                        Classifier classifierInstance = freshWekaClassifier.getClassifier();

                        // Ora addestriamo l'istanza appena creata
                        classifierInstance.buildClassifier(trainingSet);

                        aggregatedPredictionsForRun.addAll(getPredictionResults(classifierInstance, testingSet));

                        Evaluation eval = new Evaluation(trainingSet);
                        eval.evaluateModel(classifierInstance, testingSet);
                        evaluationsForRun.add(eval);
                    }

                    LOGGER.log(Level.INFO, "--- Finished Run {0} for {1}. Saving results... ---", new Object[]{run, config.getDescriptiveName()});

                    String classifierName = config.getDescriptiveName().replaceAll("\\s+", "");
                    String acumeOutputFile = String.format("%s%s_%s_run%d.csv", acumeOutputDir, project.toLowerCase(), classifierName.toLowerCase(), run);
                    AcumeUtils.exportToAcumeCsv(acumeOutputFile, aggregatedPredictionsForRun);

                    int buggyClassIndex = this.fullDataset.classAttribute().indexOfValue("yes");
                    double avgPrecision = evaluationsForRun.stream().mapToDouble(e -> e.precision(buggyClassIndex)).average().orElse(Double.NaN);
                    double avgRecall = evaluationsForRun.stream().mapToDouble(e -> e.recall(buggyClassIndex)).average().orElse(Double.NaN);
                    double avgAuc = evaluationsForRun.stream().mapToDouble(e -> e.areaUnderROC(buggyClassIndex)).average().orElse(Double.NaN);
                    double avgKappa = evaluationsForRun.stream().mapToDouble(Evaluation::kappa).average().orElse(Double.NaN);
                    double avgF1 = evaluationsForRun.stream().mapToDouble(e -> e.fMeasure(buggyClassIndex)).average().orElse(Double.NaN);

                    Metrics metrics = new Metrics(avgPrecision, avgRecall, avgAuc, avgKappa, avgF1);
                    EvaluationResult result = new EvaluationResult(project, run, config, metrics);

                    synchronized (this.crossValResults) {
                        this.crossValResults.add(result);
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "An error occurred while processing classifier config {0}: {1}", new Object[]{config.getDescriptiveName(), e});
            }
        });

        LOGGER.info("Finished all parallel tasks. Weka results and ACUME input files are generated.");
    }


    // --- HELPER METHODS ---

    private List<PredictionResult> getPredictionResults(Classifier clf, Instances data) throws Exception {
        List<PredictionResult> results = new ArrayList<>();
        int buggyClassIndex = data.classAttribute().indexOfValue("yes");
        int locIndex = data.attribute("LOC").index();

        for (int i = 0; i < data.numInstances(); i++) {
            Instance inst = data.instance(i);
            double[] distribution = clf.distributionForInstance(inst);
            double probability = distribution[buggyClassIndex];
            int loc = (int) inst.value(locIndex);
            boolean isBuggy = inst.classValue() == buggyClassIndex;
            results.add(new PredictionResult(probability, loc, isBuggy));
        }
        return results;
    }

    private Instances filterInstancesByRelease(Instances data, int attrIndex, int releaseValue, boolean lessThanOrEqual) {
        Instances filteredData = new Instances(data, 0);
        for (Instance inst : data) {
            if (lessThanOrEqual) {
                if (inst.value(attrIndex) <= releaseValue) filteredData.add(inst);
            } else {
                if (inst.value(attrIndex) == releaseValue) filteredData.add(inst);
            }
        }
        return filteredData;
    }


    private void saveResults(String type, List<EvaluationResult> evaluationResults) throws IOException {
        LOGGER.info("Saving Weka evaluation results...");
        PrintUtils.printEvaluationResults(project, evaluationResults, type);
        LOGGER.info("Evaluation results saved. ACUME input files generated in output/ folder.");
    }

    public Instances getFullDataset() {
        return this.fullDataset;
    }
}