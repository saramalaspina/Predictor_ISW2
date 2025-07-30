package model;

import weka.classifiers.Classifier;

public class WekaClassifier {
    private final Classifier classifier;
    private final String name;
    private final String sampling;
    private final String featureSelection;
    private final String costSensitive;

    public WekaClassifier(Classifier classifier, String name, String sampling, String featureSelection, String costSensitive) {
        this.classifier = classifier;
        this.name = name;
        this.sampling = sampling;
        this.featureSelection = featureSelection;
        this.costSensitive = costSensitive;
    }

    public Classifier getClassifier() {
        return classifier;
    }

    public String getName() {
        return name;
    }

    public String getSampling() {
        return sampling;
    }

    public String getFeatureSelection() {
        return featureSelection;
    }

    public String getCostSensitive() {
        return costSensitive;
    }
}
