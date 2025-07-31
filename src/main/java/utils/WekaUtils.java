package utils;

import model.JavaMethod;
import weka.core.Instances;
import weka.core.converters.ArffSaver;
import weka.core.converters.CSVLoader;

import java.io.File;
import java.io.IOException;
import java.util.List;

import weka.core.Attribute;
import weka.core.DenseInstance;

import java.util.ArrayList;
import java.util.Arrays;




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

    public static Instances buildInstances(List<JavaMethod> methods, String relationName) {
        ArrayList<Attribute> attributes = new ArrayList<>();

        // Add all numeric attributes in the correct order
        attributes.add(new Attribute("Release"));
        attributes.add(new Attribute("LOC"));
        attributes.add(new Attribute("#Parameters"));
        attributes.add(new Attribute("#Authors"));
        attributes.add(new Attribute("#Revisions"));
        attributes.add(new Attribute("StmtAdded"));
        attributes.add(new Attribute("StmtDeleted"));
        attributes.add(new Attribute("MaxChurn"));
        attributes.add(new Attribute("AvgChurn"));
        attributes.add(new Attribute("#Branches"));
        attributes.add(new Attribute("NestingDepth"));
        attributes.add(new Attribute("NFix"));
        attributes.add(new Attribute("ComplexityDensity"));

        // Add the nominal class attribute (the one we want to predict)
        List<String> classValues = Arrays.asList("no", "yes");
        attributes.add(new Attribute("Buggy", classValues));

        // Create the Instances object with the defined structure
        // The initial capacity is set to the number of methods for efficiency
        Instances data = new Instances(relationName, attributes, methods.size());
        data.setClassIndex(data.numAttributes() - 1); // Set the last attribute as the class

        // Populate the Instances object with data from each method
        for (JavaMethod m : methods) {
            double[] values = new double[data.numAttributes()];

            // Populate the values array. The order MUST match the attribute definition above.
            values[0] = m.getRelease().getId();
            values[1] = m.getLoc();
            values[2] = m.getNumParameters();
            values[3] = m.getNumAuthors();
            values[4] = m.getNumRevisions();
            values[5] = m.getTotalStmtAdded();
            values[6] = m.getTotalStmtDeleted();
            values[7] = m.getMaxChurnInARevision();
            values[8] = m.getAvgChurn();
            values[9] = m.getNumberOfBranches();
            values[10] = m.getNestingDepth();
            values[11] = m.getNFix();

            // For the nominal class attribute, we use the index of the value in the list
            // "no" is at index 0, "yes" is at index 1
            values[data.classIndex()] = m.isBuggy() ? 1 : 0;

            // Add the populated instance to the dataset
            data.add(new DenseInstance(1.0, values));
        }

        return data;
    }
}
