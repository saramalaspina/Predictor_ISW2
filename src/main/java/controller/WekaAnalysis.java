package controller;

import model.EvaluationResult;
import model.PredictionResult;
import model.WekaClassifier;
import utils.AcumeUtils;
import utils.PrintUtils;
import utils.WekaUtils;
import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.core.Attribute;
import weka.core.Instance;
import weka.core.Instances;
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
        LOGGER.info("Loading full dataset from CSV: " + datasetCsvPath);

        Instances rawData = WekaUtils.loadInstancesFromCsv(datasetCsvPath);

        Remove removeFilter = new Remove();
        removeFilter.setAttributeIndices("1"); // Rimuove la prima colonna (FullyQualifiedName)
        removeFilter.setInputFormat(rawData);
        this.fullDataset = Filter.useFilter(rawData, removeFilter);

        if (this.fullDataset.classIndex() == -1) {
            this.fullDataset.setClassIndex(this.fullDataset.numAttributes() - 1);
        }
    }

    // --- WALK-FORWARD ANALYSIS ---

    public void executeWalkForward() {
        LOGGER.log(Level.INFO, "--- Starting WALK-FORWARD analysis for project: {0} ---", project);
        try {
            runWalkForwardClassification();
            saveResults("walkForward", this.walkForwardResults);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "An error occurred during Walk-Forward analysis", e);
        }
        LOGGER.log(Level.INFO, "--- Walk-Forward analysis finished for project: {0} ---", project);
    }

    private void runWalkForwardClassification() throws Exception {
        LOGGER.info("Starting walk-forward classification and ACUME file generation...");
        String acumeOutputDir = String.format("acumeFiles/%s/walkForward/", this.project.toLowerCase());
        new File(acumeOutputDir).mkdirs();

        Attribute releaseAttr = this.fullDataset.attribute("Release");
        if (releaseAttr == null) throw new IllegalStateException("Dataset must have a 'Release' attribute.");
        int releaseIndex = releaseAttr.index();

        int maxRelease = (int) IntStream.range(0, fullDataset.numInstances())
                .mapToDouble(i -> fullDataset.instance(i).value(releaseIndex))
                .max().orElse(0);

        for (int i = 1; i < maxRelease; i++) {
            Instances trainingSet = filterInstancesByRelease(this.fullDataset, releaseIndex, i, true);
            Instances testingSet = filterInstancesByRelease(this.fullDataset, releaseIndex, i + 1, false);

            if (testingSet.isEmpty()) continue;

            LOGGER.log(Level.INFO, "--- WF Iteration {0}: Training on {1}, Testing on {2} ---", new Object[]{i, trainingSet.numInstances(), testingSet.numInstances()});
            performSingleClassification(trainingSet, testingSet, this.walkForwardResults, acumeOutputDir, "wf_iter" + i, i);
        }
    }

    // --- CROSS-VALIDATION ANALYSIS ---

    public void executeCrossValidation(int numRuns, int numFolds) {
        System.out.println("Cross Validation " + numRuns + " Times " + numFolds + " Folds");
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

    private void runCrossValidationClassification(int numRuns, int numFolds) throws Exception {
        LOGGER.info("Starting MEMORY-OPTIMIZED classification and ACUME file generation...");
        String acumeOutputDir = String.format("acumeFiles/%s/crossValidation/", this.project.toLowerCase());
        new File(acumeOutputDir).mkdirs();

        List<WekaClassifier> classifiersToTest = ClassifierBuilder.buildClassifiers(this.fullDataset);
        int totalClassifiers = classifiersToTest.size();
        int classifierCount = 0;

        // Loop esterno sui classificatori
        for (WekaClassifier wekaClf : classifiersToTest) {
            classifierCount++;
            // <-- NUOVO LOG: Indica quale classificatore sta per essere processato
            LOGGER.log(Level.INFO, "==========================================================");
            LOGGER.log(Level.INFO, "Processing Classifier {0}/{1}: {2}", new Object[]{classifierCount, totalClassifiers, wekaClf.getDescriptiveName()});
            LOGGER.log(Level.INFO, "==========================================================");

            // Per ogni run, calcoliamo le medie e salviamo i risultati per QUESTO classificatore
            for (int run = 1; run <= numRuns; run++) {
                // <-- NUOVO LOG: Inizio di una nuova run
                LOGGER.log(Level.INFO, "--- Starting Run {0}/{1} for classifier {2} ---", new Object[]{run, numRuns, wekaClf.getDescriptiveName()});

                List<PredictionResult> aggregatedPredictionsForRun = new ArrayList<>();
                List<Evaluation> evaluationsForRun = new ArrayList<>();

                Random rand = new Random(run);
                Instances randData = new Instances(this.fullDataset);
                randData.randomize(rand);
                if (randData.classAttribute().isNominal()) {
                    randData.stratify(numFolds);
                }

                for (int fold = 0; fold < numFolds; fold++) {
                    // <-- NUOVO LOG: Inizio di un nuovo fold
                    LOGGER.log(Level.FINE, "Processing fold {0}/{1}...", new Object[]{fold + 1, numFolds});

                    Instances trainingSet = randData.trainCV(numFolds, fold, rand);
                    Instances testingSet = randData.testCV(numFolds, fold);
                    if (testingSet.isEmpty()) continue;

                    // Addestra e valuta solo il classificatore corrente
                    wekaClf.getClassifier().buildClassifier(trainingSet);

                    aggregatedPredictionsForRun.addAll(getPredictionResults(wekaClf.getClassifier(), testingSet));

                    Evaluation eval = new Evaluation(trainingSet);
                    eval.evaluateModel(wekaClf.getClassifier(), testingSet);
                    evaluationsForRun.add(eval);
                } // Fine loop fold

                // <-- NUOVO LOG: Fine di una run e salvataggio dei risultati
                LOGGER.log(Level.INFO, "--- Finished Run {0}. Saving aggregated results... ---", run);

                // Ora che la run è finita, salva i risultati per questo classificatore
                String classifierName = wekaClf.getDescriptiveName();
                String acumeOutputFile = String.format("%s%s_%s_run%d.csv",
                        acumeOutputDir, project.toLowerCase(), classifierName.toLowerCase(), run);
                AcumeUtils.exportToAcumeCsv(acumeOutputFile, aggregatedPredictionsForRun);

                int buggyClassIndex = this.fullDataset.classAttribute().indexOfValue("yes");
                double avgPrecision = evaluationsForRun.stream().mapToDouble(e -> e.precision(buggyClassIndex)).average().orElse(Double.NaN);
                double avgRecall = evaluationsForRun.stream().mapToDouble(e -> e.recall(buggyClassIndex)).average().orElse(Double.NaN);
                double avgAuc = evaluationsForRun.stream().mapToDouble(e -> e.areaUnderROC(buggyClassIndex)).average().orElse(Double.NaN);
                double avgKappa = evaluationsForRun.stream().mapToDouble(Evaluation::kappa).average().orElse(Double.NaN);
                double avgF1 = evaluationsForRun.stream().mapToDouble(e -> e.fMeasure(buggyClassIndex)).average().orElse(Double.NaN);

                EvaluationResult result = new EvaluationResult(
                        project, run, wekaClf.getName(),
                        wekaClf.getFeatureSelection(), wekaClf.getSampling(), wekaClf.getCostSensitive(),
                        avgPrecision, avgRecall, avgAuc, avgKappa, avgF1
                );
                this.crossValResults.add(result);
            } // Fine loop run
        } // Fine loop classificatori
        LOGGER.info("Finished generating Weka results and AGGREGATED ACUME input files.");
    }

    // --- CORE CLASSIFICATION LOGIC ---

    private void performSingleClassification(Instances trainingSet, Instances testingSet, List<EvaluationResult> resultsList, String acumeDir, String fileSuffix, int iterationId) throws Exception {
        List<WekaClassifier> classifiersToTest = ClassifierBuilder.buildClassifiers(trainingSet);
        int buggyClassIndex = trainingSet.classAttribute().indexOfValue("yes");

        for (WekaClassifier wekaClf : classifiersToTest) {
            wekaClf.getClassifier().buildClassifier(trainingSet);
            List<PredictionResult> predictionResults = getPredictionResults(wekaClf.getClassifier(), testingSet);

            String descriptiveFileName = wekaClf.getDescriptiveName() + "_" + fileSuffix + ".csv";
            String acumeOutputFile = acumeDir + project.toLowerCase() + "_" + descriptiveFileName;
            AcumeUtils.exportToAcumeCsv(acumeOutputFile, predictionResults);

            Evaluation eval = new Evaluation(trainingSet);
            eval.evaluateModel(wekaClf.getClassifier(), testingSet);

            EvaluationResult result = new EvaluationResult(
                    project, iterationId, wekaClf.getName(),
                    wekaClf.getFeatureSelection(), wekaClf.getSampling(), wekaClf.getCostSensitive(),
                    eval.precision(buggyClassIndex), eval.recall(buggyClassIndex),
                    eval.areaUnderROC(buggyClassIndex), eval.kappa(), eval.fMeasure(buggyClassIndex)
            );
            resultsList.add(result);
        }
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