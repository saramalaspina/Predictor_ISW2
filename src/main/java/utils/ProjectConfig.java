package utils;

public enum ProjectConfig {
    OPENJPA(
            "OPENJPA",
            "convert",  // AFMethod
            "NestingDepth",      //AFeature
            "RandomForest",     // BEST_CLF_NAME
            "None",            // BEST_SAMPLING
            "None",           // BEST_FS (Feature Selection)
            "None",          // BEST_CS (Cost Sensitive)
            1,              // N_RUNS per Cross-Validation
            2              // N_Folds per Cross-Validation
    ),
    BOOKKEEPER(
            "BOOKKEEPER",
            "main",  // AFMethod
            "LOC",            //AFeature
            "RandomForest",  // BEST_CLF_NAME
            "None",         // BEST_SAMPLING
            "None",        // BEST_FS
            "None",       // BEST_CS
            10,          // N_RUNS per Cross-Validation
            10          // N_Folds per Cross-Validation
    );


    private final String projectName;
    private final String AFMethod;
    private final String AFeature;
    private final String bestClassifierName;
    private final String bestSampling;
    private final String bestFeatureSelection;
    private final String bestCostSensitive;
    private final int crossValidationRuns;
    private final int crossValidationFolds;

    ProjectConfig(String projectName, String AFMethod, String AFeature, String bestClfName, String bestSampling, String bestFs, String bestCs, int cvRuns, int cvFolds) {
        this.projectName = projectName;
        this.AFMethod = AFMethod;
        this.AFeature = AFeature;
        this.bestClassifierName = bestClfName;
        this.bestSampling = bestSampling;
        this.bestFeatureSelection = bestFs;
        this.bestCostSensitive = bestCs;
        this.crossValidationRuns = cvRuns;
        this.crossValidationFolds = cvFolds;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getBestClassifierName() {
        return bestClassifierName;
    }

    public String getBestSampling() {
        return bestSampling;
    }

    public String getBestFeatureSelection() {
        return bestFeatureSelection;
    }

    public String getBestCostSensitive() {
        return bestCostSensitive;
    }

    public int getCrossValidationRuns() {
        return crossValidationRuns;
    }

    public int getCrossValidationFolds() {
        return crossValidationFolds;
    }

    public String getAFeature() {
        return AFeature;
    }

    public String getAFMethod() {
        return AFMethod;
    }

    // Factory method to obtain the right configuration from the project name
    public static ProjectConfig fromString(String projectName) {
        for (ProjectConfig config : ProjectConfig.values()) {
            if (config.projectName.equalsIgnoreCase(projectName)) {
                return config;
            }
        }
        throw new IllegalArgumentException("Nessuna configurazione trovata per il progetto: " + projectName);
    }
}
