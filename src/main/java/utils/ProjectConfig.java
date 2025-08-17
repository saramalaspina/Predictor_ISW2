package utils;

public enum ProjectConfig {
    // Definizione delle configurazioni per ogni progetto
    OPENJPA(
            "OPENJPA",
            "RandomForest", // BEST_CLF_NAME
            "None",         // BEST_SAMPLING
            "None",         // BEST_FS (Feature Selection)
            "None",         // BEST_CS (Cost Sensitive)
            1              // N_RUNS per Cross-Validation
    ),
    BOOKKEEPER(
            "BOOKKEEPER",
            "RandomForest",   // BEST_CLF_NAME
            "None",        // BEST_SAMPLING
            "None",    // BEST_FS
            "None",         // BEST_CS
            10               // N_RUNS per Cross-Validation
    );

    // Campi per memorizzare i valori delle costanti
    private final String projectName;
    private final String bestClassifierName;
    private final String bestSampling;
    private final String bestFeatureSelection;
    private final String bestCostSensitive;
    private final int crossValidationRuns;

    // Costruttore privato per inizializzare i campi
    ProjectConfig(String projectName, String bestClfName, String bestSampling, String bestFs, String bestCs, int cvRuns) {
        this.projectName = projectName;
        this.bestClassifierName = bestClfName;
        this.bestSampling = bestSampling;
        this.bestFeatureSelection = bestFs;
        this.bestCostSensitive = bestCs;
        this.crossValidationRuns = cvRuns;
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

    //Metodo factory per ottenere la configurazione dal nome del progetto
    public static ProjectConfig fromString(String projectName) {
        for (ProjectConfig config : ProjectConfig.values()) {
            if (config.projectName.equalsIgnoreCase(projectName)) {
                return config;
            }
        }
        throw new IllegalArgumentException("Nessuna configurazione trovata per il progetto: " + projectName);
    }
}
