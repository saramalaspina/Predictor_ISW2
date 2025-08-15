package controller;

import model.EvaluationResult;
import model.JavaMethod;
import model.PredictionResult;
import model.Release;
import model.WekaClassifier;
import utils.AcumeUtils;
import utils.PrintUtils;
import utils.WekaUtils;
import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.ArffSaver;
import weka.core.converters.ConverterUtils.DataSource;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;


public class WekaAnalysis {

    private static final Logger LOGGER = Logger.getLogger(WekaAnalysis.class.getName());

    private final String project;
    private final List<JavaMethod> allMethods;
    private final List<Release> releasesToAnalyze;
    private final List<EvaluationResult> walkForwardResults;
    private final List<EvaluationResult> crossValResults;

    public WekaAnalysis(String project, List<JavaMethod> allMethods, List<Release> releasesToAnalyze) {
        this.project = project;
        this.allMethods = allMethods;
        this.releasesToAnalyze = releasesToAnalyze;
        this.walkForwardResults = new ArrayList<>();
        this.crossValResults = new ArrayList<>();
    }

    public void executeWalkForward() {
        LOGGER.log(Level.INFO, "--- Starting WALK-FORWARD analysis for project: {0} ---", project);
        try {
            prepareWalkForwardData();
            runWalkForwardClassification();
            saveResults("walkForward", this.walkForwardResults);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "An error occurred during Walk-Forward analysis", e);
        }
        LOGGER.log(Level.INFO, "--- Walk-Forward analysis finished for project: {0} ---", project);
    }

    private void prepareWalkForwardData() throws IOException {
        LOGGER.info("Preparing data for walk-forward validation...");
        for (int i = 1; i < releasesToAnalyze.size(); i++) {
            // Crea una copia final della variabile 'i' per usarla nella lambda.
            final int currentReleaseId = i;

            // Usa 'currentReleaseId' invece di 'i' nelle lambda.
            List<JavaMethod> trainingMethods = allMethods.stream()
                    .filter(m -> m.getRelease().getId() <= currentReleaseId)
                    .collect(Collectors.toList());

            List<JavaMethod> testingMethods = allMethods.stream()
                    .filter(m -> m.getRelease().getId() == currentReleaseId + 1)
                    .collect(Collectors.toList());

            if (testingMethods.isEmpty()) {
                LOGGER.log(Level.WARNING, "Skipping walk-forward iteration {0}: No methods in testing release {1}", new Object[]{currentReleaseId, currentReleaseId + 1});
                continue;
            }

            String iterDir = "arffFiles/" + project.toLowerCase() + "/walkForward/iteration_" + currentReleaseId;
            new File(iterDir).mkdirs();

            Instances trainingSet = WekaUtils.buildInstances(trainingMethods, project + "_Training_WF_" + currentReleaseId);
            Instances testingSet = WekaUtils.buildInstances(testingMethods, project + "_Testing_WF_" + currentReleaseId);

            ArffSaver saver = new ArffSaver();
            saver.setInstances(trainingSet);
            saver.setFile(new File(iterDir + "/Training.arff"));
            saver.writeBatch();
            saver.setInstances(testingSet);
            saver.setFile(new File(iterDir + "/Testing.arff"));
            saver.writeBatch();
        }
        LOGGER.info("Walk-forward data preparation complete.");
    }

    private void runWalkForwardClassification() throws Exception {
        LOGGER.info("Starting walk-forward classification and ACUME file generation...");
        String acumeOutputDir = "acumeFiles/" + this.project.toLowerCase() +"/acume_input_walkforward/";
        new File(acumeOutputDir).mkdirs();

        for (int i = 1; i < releasesToAnalyze.size(); i++) {
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
            performSingleClassification(trainingSet, testingSet, this.walkForwardResults, acumeOutputDir, "wf_iter" + i, i);
        }
    }

    public void executeCrossValidation() {
        LOGGER.log(Level.INFO, "--- Starting 10x10 FOLD analysis for project: {0} ---", project);
        try {
            Instances fullDataset = prepareFullDataset();

            int numRuns = 10;
            int numFolds = 10;

            prepareCrossValidationData(fullDataset, numRuns, numFolds);
            runCrossValidationClassification(numRuns, numFolds);
            saveResults("crossValidation", this.crossValResults);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "An error occurred during 10x10 fold analysis", e);
        }
        LOGGER.log(Level.INFO, "--- 10x10 FOLD analysis finished for project: {0} ---", project);
    }

    private Instances prepareFullDataset() throws IOException {
        LOGGER.info("Preparing full dataset...");
        String arffDir = "arffFiles/" + project.toLowerCase();
        new File(arffDir).mkdirs();
        String arffPath = arffDir + "/FullDataset.arff";

        Instances fullDataset = WekaUtils.buildInstances(this.allMethods, project + "_full");
        ArffSaver saver = new ArffSaver();
        saver.setInstances(fullDataset);
        saver.setFile(new File(arffPath));
        saver.writeBatch();
        return fullDataset;
    }

    private void prepareCrossValidationData(Instances fullDataset, int numRuns, int numFolds) throws IOException {
        LOGGER.info("Preparing data for 10-times 10-fold cross-validation...");
        ArffSaver saver = new ArffSaver();
        for (int run = 1; run <= numRuns; run++) {
            Random rand = new Random(run);
            Instances randData = new Instances(fullDataset);
            randData.randomize(rand);
            if (randData.classAttribute().isNominal()) {
                randData.stratify(numFolds);
            }
            for (int fold = 0; fold < numFolds; fold++) {
                Instances train = randData.trainCV(numFolds, fold, rand);
                Instances test = randData.testCV(numFolds, fold);
                String iterDir = String.format("arffFiles/%s/crossValidation/run_%d/fold_%d", project.toLowerCase(), run, fold);
                new File(iterDir).mkdirs();
                saver.setInstances(train);
                saver.setFile(new File(iterDir + "/Training.arff"));
                saver.writeBatch();
                saver.setInstances(test);
                saver.setFile(new File(iterDir + "/Testing.arff"));
                saver.writeBatch();
            }
        }
        LOGGER.info("Cross-validation data preparation complete.");
    }

    private void runCrossValidationClassification(int numRuns, int numFolds) throws Exception {
        LOGGER.info("Starting AGGREGATED classification on prepared folds and ACUME file generation...");
        String acumeOutputDir = "acumeFiles/" + this.project.toLowerCase() + "/acume_input_crossval/";
        new File(acumeOutputDir).mkdirs();

        // Ottieni tutte le configurazioni di classificatori una sola volta
        // (Presupponiamo che la struttura dei dati non cambi tra i fold)
        DataSource tempSource = new DataSource(String.format("arffFiles/%s/crossValidation/run_1/fold_0/Training.arff", project.toLowerCase()));
        Instances tempTrainingSet = tempSource.getDataSet();
        tempTrainingSet.setClassIndex(tempTrainingSet.numAttributes() - 1);
        List<WekaClassifier> classifiersToTest = ClassifierBuilder.buildClassifiers(tempTrainingSet);

        for (int run = 1; run <= numRuns; run++) {
            LOGGER.log(Level.INFO, "--- CV Run {0}/{1} ---", new Object[]{run, numRuns});

            // Crea una mappa per aggregare i risultati per ogni classificatore in questa run
            Map<String, List<PredictionResult>> aggregatedPredictions = new HashMap<>();
            for (WekaClassifier wekaClf : classifiersToTest) {
                aggregatedPredictions.put(wekaClf.getDescriptiveName(), new ArrayList<>());
            }

            Map<String, List<Evaluation>> evaluationsPerClassifier = new HashMap<>();
            for (WekaClassifier wekaClf : classifiersToTest) {
                evaluationsPerClassifier.put(wekaClf.getDescriptiveName(), new ArrayList<>());
            }

            for (int fold = 0; fold < numFolds; fold++) {
                String dirPath = String.format("arffFiles/%s/crossValidation/run_%d/fold_%d/", project.toLowerCase(), run, fold);
                DataSource trainingSource = new DataSource(dirPath + "Training.arff");
                Instances trainingSet = trainingSource.getDataSet();
                trainingSet.setClassIndex(trainingSet.numAttributes() - 1);

                DataSource testingSource = new DataSource(dirPath + "Testing.arff");
                Instances testingSet = testingSource.getDataSet();
                testingSet.setClassIndex(testingSet.numAttributes() - 1);

                if (testingSet.isEmpty()) continue;

                for (WekaClassifier wekaClf : classifiersToTest) {
                    wekaClf.getClassifier().buildClassifier(trainingSet);

                    // Aggrega le predizioni per ACUME
                    List<PredictionResult> foldPredictions = getPredictionResults(wekaClf.getClassifier(), testingSet);
                    aggregatedPredictions.get(wekaClf.getDescriptiveName()).addAll(foldPredictions);

                    // Aggrega le valutazioni di Weka
                    Evaluation eval = new Evaluation(trainingSet);
                    eval.evaluateModel(wekaClf.getClassifier(), testingSet);
                    evaluationsPerClassifier.get(wekaClf.getDescriptiveName()).add(eval);
                }
            } // Fine del loop sui fold

            // Ora, per ogni classificatore, salva il file ACUME aggregato e calcola le metriche medie di Weka
            for (WekaClassifier wekaClf : classifiersToTest) {
                String classifierName = wekaClf.getDescriptiveName();

                // 1. Salva il file ACUME aggregato per questa run
                List<PredictionResult> runPredictions = aggregatedPredictions.get(classifierName);
                String acumeOutputFile = String.format("%s%s_%s_run%d.csv",
                        acumeOutputDir, project.toLowerCase(), classifierName.toLowerCase(), run);
                AcumeUtils.exportToAcumeCsv(acumeOutputFile, runPredictions);

                // 2. Calcola le medie delle metriche Weka per questa run
                List<Evaluation> evals = evaluationsPerClassifier.get(classifierName);
                double avgPrecision = evals.stream().mapToDouble(e -> e.precision(1)).average().orElse(Double.NaN);
                double avgRecall = evals.stream().mapToDouble(e -> e.recall(1)).average().orElse(Double.NaN);
                double avgAuc = evals.stream().mapToDouble(e -> e.areaUnderROC(1)).average().orElse(Double.NaN);
                double avgKappa = evals.stream().mapToDouble(Evaluation::kappa).average().orElse(Double.NaN);
                double avgF1 = evals.stream().mapToDouble(e -> e.fMeasure(1)).average().orElse(Double.NaN);

                EvaluationResult result = new EvaluationResult(
                        project, run, wekaClf.getName(),
                        wekaClf.getFeatureSelection(), wekaClf.getSampling(), wekaClf.getCostSensitive(),
                        avgPrecision, avgRecall, avgAuc, avgKappa, avgF1
                );
                this.crossValResults.add(result);
            }
        } // Fine del loop sulle run
        LOGGER.info("Finished generating Weka results and AGGREGATED ACUME input files.");
    }

    private void performSingleClassification(Instances trainingSet, Instances testingSet, List<EvaluationResult> resultsList, String acumeDir, String fileSuffix, int iterationId) throws Exception {
        List<WekaClassifier> classifiersToTest = ClassifierBuilder.buildClassifiers(trainingSet);
        int buggyClassIndex = trainingSet.classAttribute().indexOfValue("yes");

        for (WekaClassifier wekaClf : classifiersToTest) {
            // Train Classifier
            wekaClf.getClassifier().buildClassifier(trainingSet);

            // Generate ACUME input file
            List<PredictionResult> predictionResults = getPredictionResults(wekaClf.getClassifier(), testingSet);

            // Costruisci un nome descrittivo basato sulla configurazione completa
            StringBuilder fileNameBuilder = new StringBuilder();
            fileNameBuilder.append(project.toLowerCase());
            fileNameBuilder.append("_").append(wekaClf.getName().toLowerCase());

            if (!"None".equalsIgnoreCase(wekaClf.getSampling())) {
                fileNameBuilder.append("_").append(wekaClf.getSampling().toLowerCase());
            }
            if (!"None".equalsIgnoreCase(wekaClf.getFeatureSelection())) {
                fileNameBuilder.append("_fs");
            }
            if (!"None".equalsIgnoreCase(wekaClf.getCostSensitive())) {
                fileNameBuilder.append("_cs");
            }

            fileNameBuilder.append("_").append(fileSuffix);
            fileNameBuilder.append(".csv");

            String descriptiveFileName = fileNameBuilder.toString();

            String acumeOutputFile = acumeDir + descriptiveFileName;
            AcumeUtils.exportToAcumeCsv(acumeOutputFile, predictionResults);

            // Evaluate with Weka and store results (without NPofB20)
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

    private List<PredictionResult> getPredictionResults(Classifier clf, Instances data) throws Exception {
        List<PredictionResult> results = new ArrayList<>();
        int buggyClassIndex = data.classAttribute().indexOfValue("yes");
        int locIndex = data.attribute("LOC").index();

        if (buggyClassIndex == -1) throw new IllegalStateException("Class attribute 'Buggy=yes' not found.");
        if (locIndex == -1) throw new IllegalStateException("Feature 'LOC' not found.");

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

    private void saveResults(String type, List<EvaluationResult> evaluationResults) throws IOException {
        LOGGER.info("Saving Weka evaluation results...");
        PrintUtils.printEvaluationResults(project, evaluationResults, type);
        LOGGER.info("Evaluation results saved successfully. ACUME input files generated in output/ folder.");
    }
}