package com.irvil.nntextclassifier.recognizer;

import com.irvil.nntextclassifier.model.Characteristic;
import com.irvil.nntextclassifier.model.CharacteristicValue;
import com.irvil.nntextclassifier.model.IncomingCall;
import com.irvil.nntextclassifier.model.VocabularyWord;
import com.irvil.nntextclassifier.ngram.NGramStrategy;
import com.irvil.nntextclassifier.observer.Observable;
import com.irvil.nntextclassifier.observer.Observer;
import org.encog.Encog;
import org.encog.engine.network.activation.ActivationSigmoid;
import org.encog.ml.data.basic.BasicMLDataSet;
import org.encog.neural.networks.BasicNetwork;
import org.encog.neural.networks.layers.BasicLayer;
import org.encog.neural.networks.training.propagation.Propagation;
import org.encog.neural.networks.training.propagation.resilient.ResilientPropagation;
import org.encog.persist.PersistError;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.encog.persist.EncogDirectoryPersistence.loadObject;
import static org.encog.persist.EncogDirectoryPersistence.saveObject;

// todo: create Test class
public class Recognizer implements Observable {
  private final Characteristic characteristic;
  private final int inputLayerSize;
  private final int outputLayerSize;
  private final BasicNetwork network;
  private final List<VocabularyWord> vocabulary;
  private final NGramStrategy nGram;
  private List<Observer> observers = new ArrayList<>();

  public Recognizer(File trainedNetwork, Characteristic characteristic, List<VocabularyWord> vocabulary, NGramStrategy nGram) {
    if (characteristic == null ||
        characteristic.getName().equals("") ||
        characteristic.getPossibleValues() == null ||
        characteristic.getPossibleValues().size() == 0 ||
        vocabulary == null ||
        vocabulary.size() == 0 ||
        nGram == null) {
      throw new IllegalArgumentException();
    }

    this.characteristic = characteristic;
    this.vocabulary = vocabulary;
    this.inputLayerSize = vocabulary.size();
    this.outputLayerSize = characteristic.getPossibleValues().size();
    this.nGram = nGram;

    if (trainedNetwork == null) {
      this.network = createNeuralNetwork();
    } else {
      // load neural network from file
      try {
        this.network = (BasicNetwork) loadObject(trainedNetwork);
      } catch (PersistError e) {
        throw new IllegalArgumentException();
      }
    }
  }

  public Recognizer(Characteristic characteristic, List<VocabularyWord> vocabulary, NGramStrategy nGram) {
    this(null, characteristic, vocabulary, nGram);
  }

  public static void shutdown() {
    Encog.getInstance().shutdown();
  }

  private BasicNetwork createNeuralNetwork() {
    BasicNetwork network = new BasicNetwork();

    // input layer
    network.addLayer(new BasicLayer(null, true, inputLayerSize));

    // hidden layer
    network.addLayer(new BasicLayer(new ActivationSigmoid(), true, inputLayerSize / 2));

    // output layer
    network.addLayer(new BasicLayer(new ActivationSigmoid(), false, outputLayerSize));

    network.getStructure().finalizeStructure();
    network.reset();

    return network;
  }

  public CharacteristicValue recognize(IncomingCall incomingCall) {
    double[] output = new double[outputLayerSize];

    // calculate output vector
    network.compute(getTextAsVectorOfWords(incomingCall), output);
    Encog.getInstance().shutdown();

    return convertVectorToCharacteristic(output);
  }

  private CharacteristicValue convertVectorToCharacteristic(double[] vector) {
    int idOfMaxValue = getIdOfMaxValue(vector);

    // find CharacteristicValue with found Id
    //

    for (CharacteristicValue c : characteristic.getPossibleValues()) {
      if (c.getId() == idOfMaxValue) {
        return c;
      }
    }

    return null;
  }

  private int getIdOfMaxValue(double[] vector) {
    int indexOfMaxValue = 0;
    double maxValue = vector[0];

    for (int i = 1; i < vector.length; i++) {
      if (vector[i] > maxValue) {
        maxValue = vector[i];
        indexOfMaxValue = i;
      }
    }

    return indexOfMaxValue + 1;
  }

  public void saveTrainedRecognizer(File trainedNetwork) {
    saveObject(trainedNetwork, network);
    notifyObservers("Trained Recognizer for Characteristics '" + characteristic.getName() + "' saved. Wait...");
  }

  public String getCharacteristicName() {
    return characteristic.getName();
  }

  public void train(List<IncomingCall> incomingCalls) {
    // prepare input and ideal vectors
    // input <- IncomingCall text vector
    // ideal <- characteristicValue vector
    //

    double[][] input = getInput(incomingCalls);
    double[][] ideal = getIdeal(incomingCalls);

    // train
    //

    Propagation train = new ResilientPropagation(network, new BasicMLDataSet(input, ideal));
    train.setThreadCount(16);

    do {
      train.iteration();
      notifyObservers("Training Recognizer for Characteristics '" + characteristic.getName() + "'. Errors: " + String.format("%.2f", train.getError() * 100) + "%. Wait...");
    } while (train.getError() > 0.01);

    train.finishTraining();
    notifyObservers("Recognizer for Characteristics '" + characteristic.getName() + "' trained. Wait...");
  }

  private double[][] getInput(List<IncomingCall> incomingCalls) {
    double[][] input = new double[incomingCalls.size()][inputLayerSize];

    // convert all incoming call texts to vectors
    //

    int i = 0;

    for (IncomingCall incomingCall : incomingCalls) {
      input[i++] = getTextAsVectorOfWords(incomingCall);
    }

    return input;
  }

  private double[][] getIdeal(List<IncomingCall> incomingCalls) {
    double[][] ideal = new double[incomingCalls.size()][outputLayerSize];

    // convert all incoming call characteristics to vectors
    //

    int i = 0;

    for (IncomingCall incomingCall : incomingCalls) {
      ideal[i++] = getCharacteristicAsVector(incomingCall);
    }

    return ideal;
  }

  // example:
  // count = 5; id = 4;
  // vector = {0, 0, 0, 1, 0}
  private double[] getCharacteristicAsVector(IncomingCall incomingCall) {
    double[] vector = new double[outputLayerSize];
    vector[incomingCall.getCharacteristicValue(characteristic).getId() - 1] = 1;
    return vector;
  }

  private double[] getTextAsVectorOfWords(IncomingCall incomingCall) {
    double[] vector = new double[inputLayerSize];

    // convert text to nGram
    Set<String> uniqueValues = nGram.getNGram(incomingCall.getText());

    // create vector
    //

    for (String word : uniqueValues) {
      VocabularyWord vw = findWordInVocabulary(word);

      if (vw != null) { // word found in vocabulary
        vector[vw.getId() - 1] = 1;
      }
    }

    return vector;
  }

  private VocabularyWord findWordInVocabulary(String word) {
    try {
      return vocabulary.get(vocabulary.indexOf(new VocabularyWord(word)));
    } catch (NullPointerException | IndexOutOfBoundsException e) {
      return null;
    }
  }

  @Override
  public String toString() {
    return characteristic.getName() + "RecognizerNeuralNetwork";
  }

  @Override
  public void addObserver(Observer o) {
    observers.add(o);
  }

  @Override
  public void removeObserver(Observer o) {
    observers.remove(o);
  }

  @Override
  public void notifyObservers(String text) {
    for (Observer o : observers) {
      o.update(text);
    }
  }
}