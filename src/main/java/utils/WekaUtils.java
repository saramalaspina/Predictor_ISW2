package utils;

import weka.core.Instances;
import weka.core.converters.ArffSaver;
import weka.core.converters.CSVLoader;

import java.io.File;
import java.io.IOException;


public class WekaUtils {
    // Convert CSV to file ARFF
    public static void convertCsvToArff(String sourceCsvPath, String destArffPath) throws IOException {
        // Load CSV
        CSVLoader loader = new CSVLoader();
        loader.setSource(new File(sourceCsvPath));
        Instances data = loader.getDataSet();

        // Salve in ARFF format
        ArffSaver saver = new ArffSaver();
        saver.setInstances(data);
        saver.setFile(new File(destArffPath));
        saver.writeBatch();
    }
}
