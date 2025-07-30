package controller;

import model.WekaClassifier;
import weka.attributeSelection.BestFirst;
import weka.attributeSelection.CfsSubsetEval;
import weka.classifiers.Classifier;
import weka.classifiers.CostMatrix;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.lazy.IBk;
import weka.classifiers.meta.CostSensitiveClassifier;
import weka.classifiers.meta.FilteredClassifier;
import weka.classifiers.trees.RandomForest;
import weka.core.AttributeStats;
import weka.core.Instances;
import weka.core.Utils;
import weka.filters.Filter;
import weka.filters.supervised.attribute.AttributeSelection;
import weka.filters.supervised.instance.SMOTE;
import weka.filters.supervised.instance.Resample;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.logging.Level;

/*  A factory class for building various WEKA classifier configurations.
    It creates combinations of base classifiers with feature selection,
    data balancing (SMOTE), and cost-sensitive techniques */

public class ClassifierBuilder {

    private ClassifierBuilder() {}

    // Method to generate a list of all classifier configurations to be tested.
    public static List<WekaClassifier> buildClassifiers(Instances trainingSet) {
        List<WekaClassifier> classifiers = new ArrayList<>();

        // Add base classifiers without any pre-processing
        addBaseClassifiers(classifiers);

        // Add classifiers with Feature Selection
        addFeatureSelectionClassifiers(classifiers);

        // Add classifiers with SMOTE for data balancing
        addSmoteClassifiers(classifiers, trainingSet);

        // Add classifiers with Cost-Sensitive learning
        addCostSensitiveClassifiers(classifiers);

        return classifiers;
    }

    // Creates and adds the three baseline classifiers (RandomForest, NaiveBayes, IBk) without any filters
    private static void addBaseClassifiers(List<WekaClassifier> classifiers) {
        classifiers.add(new WekaClassifier(new RandomForest(), "RandomForest", "None", "None", "None"));
        classifiers.add(new WekaClassifier(new NaiveBayes(), "NaiveBayes", "None", "None", "None"));
        classifiers.add(new WekaClassifier(new IBk(), "IBk", "None", "None", "None"));
    }

    // Creates classifiers wrapped in a FilteredClassifier with BestFirst feature selection
    private static void addFeatureSelectionClassifiers(List<WekaClassifier> classifiers) {
        List<Classifier> baseClassifiers = getBaseClassifiers();
        for (Classifier base : baseClassifiers) {
            FilteredClassifier fc = new FilteredClassifier();
            fc.setClassifier(base);
            fc.setFilter(createFeatureSelectionFilter());

            classifiers.add(new WekaClassifier(fc, base.getClass().getSimpleName(), "None", "BestFirst", "None"));
        }
    }

    // Creates classifiers wrapped in a FilteredClassifier with the SMOTE filter for balancing
    private static void addSmoteClassifiers(List<WekaClassifier> classifiers, Instances trainingSet) {
        List<Classifier> baseClassifiers = getBaseClassifiers();
        Filter smote = createSmoteFilter(trainingSet);

        for (Classifier base : baseClassifiers) {
            FilteredClassifier fc = new FilteredClassifier();
            fc.setClassifier(base);
            fc.setFilter(smote);

            classifiers.add(new WekaClassifier(fc, base.getClass().getSimpleName(), "SMOTE", "None", "None"));
        }
    }

    // Creates classifiers wrapped in the CostSensitiveClassifier
    private static void addCostSensitiveClassifiers(List<WekaClassifier> classifiers) {
        List<Classifier> baseClassifiers = getBaseClassifiers();
        for (Classifier base : baseClassifiers) {
            CostSensitiveClassifier csc = new CostSensitiveClassifier();
            csc.setClassifier(base);
            csc.setCostMatrix(createCostMatrix());
            csc.setMinimizeExpectedCost(false);

            classifiers.add(new WekaClassifier(csc, base.getClass().getSimpleName(), "None", "None", "SensitiveLearning"));
        }
    }


    private static List<Classifier> getBaseClassifiers() {
        List<Classifier> baseClassifiers = new ArrayList<>();
        baseClassifiers.add(new RandomForest());
        baseClassifiers.add(new NaiveBayes());
        baseClassifiers.add(new IBk());
        return baseClassifiers;
    }


    private static Filter createFeatureSelectionFilter() {
        AttributeSelection filter = new AttributeSelection();

        // Create the evaluator
        CfsSubsetEval eval = new CfsSubsetEval();

        // Create the search method
        BestFirst search = new BestFirst();

        // Configure the search method using command-line options
        // -D 1 specifies a forward search.
        try {
            search.setOptions(Utils.splitOptions("-D 1"));
        } catch (Exception e) {
            Logger.getLogger(ClassifierBuilder.class.getName()).log(Level.SEVERE, "Failed to set BestFirst options", e);
        }

        // Set the evaluator and search method on the filter
        filter.setEvaluator(eval);
        filter.setSearch(search);

        return filter;
    }


    private static Filter createSmoteFilter(Instances data) {
        SMOTE smote = new SMOTE();

        // Get class distribution
        AttributeStats stats = data.attributeStats(data.classIndex());
        int[] nominalCounts = stats.nominalCounts;

        if (nominalCounts.length < 2) return new Resample(); // Failsafe if data is not binary

        double majoritySize = Math.max(nominalCounts[0], nominalCounts[1]);
        double minoritySize = Math.min(nominalCounts[0], nominalCounts[1]);

        if (minoritySize == 0) return new Resample(); // Avoid division by zero

        // Calculate the percentage of new instances to create
        double percentage = (majoritySize - minoritySize) / minoritySize * 100.0;
        smote.setPercentage(percentage);

        return smote;
    }

    // Penalizes False Negatives (predicting 'not-buggy' for a buggy instance) 10 times more
    // than False Positives (predicting 'buggy' for a clean instance).
    private static CostMatrix createCostMatrix() {
        // Assuming class 0 = buggy (positive), class 1 = not-buggy (negative)
        // The matrix is structured as:
        //      Predicted
        //      buggy  not-buggy
        // Act. buggy   0      10 (FN)
        // Act. not-buggy 1 (FP)   0
        CostMatrix matrix = new CostMatrix(2);
        matrix.setCell(0, 0, 0.0);   // True Positive (correct)
        matrix.setCell(1, 1, 0.0);   // True Negative (correct)
        matrix.setCell(0, 1, 10.0);  // False Negative (costly mistake)
        matrix.setCell(1, 0, 1.0);   // False Positive (less costly mistake)
        return matrix;
    }
}
