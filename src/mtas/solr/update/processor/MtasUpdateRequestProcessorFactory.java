package mtas.solr.update.processor;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.util.CharFilterFactory;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.schema.FieldType;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.apache.solr.update.processor.UpdateRequestProcessorFactory;

import mtas.analysis.MtasTokenizer;
import mtas.analysis.util.MtasCharFilterFactory;
import mtas.analysis.util.MtasTokenizerFactory;
import mtas.solr.schema.MtasPreAnalyzedField;

/**
 * A factory for creating MtasUpdateRequestProcessor objects.
 */
public class MtasUpdateRequestProcessorFactory
    extends UpdateRequestProcessorFactory {

  /** The config. */
  private MtasUpdateRequestProcessorConfig config = null;

  /* (non-Javadoc)
   * @see org.apache.solr.update.processor.UpdateRequestProcessorFactory#init(org.apache.solr.common.util.NamedList)
   */
  @Override
  @SuppressWarnings("rawtypes")
  public void init(NamedList args) {
    super.init(args);
  }

  /**
   * Inits the.
   *
   * @param req the req
   * @throws IOException Signals that an I/O exception has occurred.
   */
  @SuppressWarnings("unchecked")
  private void init(SolrQueryRequest req) throws IOException {
    if (config == null) {
      // initialise
      config = new MtasUpdateRequestProcessorConfig();
      // required info
      Map<String, FieldType> fieldTypes = req.getSchema().getFieldTypes();
      Map<String, SchemaField> fields = req.getSchema().getFields();
      SolrResourceLoader resourceLoader = req.getCore().getSolrConfig()
          .getResourceLoader();
      // check fieldTypes
      for (String name : fieldTypes.keySet()) {
        // only for MtasPreAnalyzedField
        if (fieldTypes.get(name) instanceof MtasPreAnalyzedField) {
          MtasPreAnalyzedField mpaf = (MtasPreAnalyzedField) fieldTypes
              .get(name);
          config.fieldTypeDefaultConfiguration.put(name,
              mpaf.defaultConfiguration);
          config.fieldTypeConfigurationFromField.put(name,
              mpaf.configurationFromField);
          config.fieldTypeNumberOfTokensField.put(name, mpaf.setNumberOfTokens);
          config.fieldTypeNumberOfPositionsField.put(name,
              mpaf.setNumberOfPositions);
          config.fieldTypeSizeField.put(name, mpaf.setSize);
          config.fieldTypeErrorField.put(name, mpaf.setError);
          if (mpaf.followIndexAnalyzer == null
              || !fieldTypes.containsKey(mpaf.followIndexAnalyzer)) {
            throw new IOException(
                name + " can't follow " + mpaf.followIndexAnalyzer);
          } else {
            FieldType fieldType = fieldTypes.get(mpaf.followIndexAnalyzer);
            SimpleOrderedMap<?> analyzer = null;
            Object tmpObj1 = fieldType.getNamedPropertyValues(false)
                .get(FieldType.INDEX_ANALYZER);
            if (tmpObj1 != null && tmpObj1 instanceof SimpleOrderedMap) {
              analyzer = (SimpleOrderedMap<?>) tmpObj1;
            }
            if (analyzer == null) {
              Object tmpObj2 = fieldType.getNamedPropertyValues(false)
                  .get(FieldType.ANALYZER);
              if (tmpObj2 != null && tmpObj2 instanceof SimpleOrderedMap) {
                analyzer = (SimpleOrderedMap<?>) tmpObj2;
              }
            }
            if (analyzer == null) {
              throw new IOException("no analyzer");
            } else {
              // charfilters
              ArrayList<SimpleOrderedMap<Object>> listCharFilters = null;
              SimpleOrderedMap<Object> configTokenizer = null;
              try {
                listCharFilters = (ArrayList<SimpleOrderedMap<Object>>) analyzer
                    .findRecursive(FieldType.CHAR_FILTERS);
                ;
                configTokenizer = (SimpleOrderedMap<Object>) analyzer
                    .findRecursive(FieldType.TOKENIZER);
              } catch (ClassCastException e) {
                throw new IOException(
                    "could not cast charFilters and/or tokenizer from analyzer");
              }
              if (listCharFilters != null && !listCharFilters.isEmpty()) {
                CharFilterFactory[] charFilterFactories = new CharFilterFactory[listCharFilters
                    .size()];
                int number = 0;
                for (SimpleOrderedMap<Object> configCharFilter : listCharFilters) {
                  String className = null;
                  Map<String, String> args = new HashMap<String, String>();
                  Iterator<Map.Entry<String, Object>> it = configCharFilter
                      .iterator();
                  // get className and args
                  while (it.hasNext()) {
                    Map.Entry<String, Object> obj = it.next();
                    if (obj.getValue() instanceof String) {
                      if (obj.getKey().equals(FieldType.CLASS_NAME)) {
                        className = (String) obj.getValue();
                      } else {
                        args.put(obj.getKey(), (String) obj.getValue());
                      }
                    }
                  }
                  if (className != null) {
                    try {
                      Class<?> cls = Class.forName((String) className);
                      if (cls.isAssignableFrom(MtasCharFilterFactory.class)) {
                        Class<?>[] types = { Map.class,
                            SolrResourceLoader.class };
                        Constructor<?> cnstr = cls.getConstructor(types);
                        Object cff = cnstr.newInstance(args, resourceLoader);
                        if (cff instanceof MtasCharFilterFactory) {
                          charFilterFactories[number] = (MtasCharFilterFactory) cff;
                          number++;
                        } else {
                          throw new IOException(
                              className + " is no MtasCharFilterFactory");
                        }
                      } else {
                        Class<?>[] types = { Map.class };
                        Constructor<?> cnstr = cls.getConstructor(types);
                        Object cff = cnstr.newInstance(args);
                        if (cff instanceof CharFilterFactory) {
                          charFilterFactories[number] = (CharFilterFactory) cff;
                          number++;
                        } else {
                          throw new IOException(
                              className + " is no CharFilterFactory");
                        }
                      }
                    } catch (ClassNotFoundException | InstantiationException
                        | IllegalAccessException | IllegalArgumentException
                        | InvocationTargetException | NoSuchMethodException e) {
                      e.printStackTrace();
                      throw new IOException(e.getMessage());
                    }
                  } else {
                    throw new IOException("no className");
                  }
                }
                config.fieldTypeCharFilterFactories.put(name,
                    charFilterFactories);
              } else {
                config.fieldTypeCharFilterFactories.put(name, null);
              }
              if (configTokenizer != null) {
                String className = null;
                Map<String, String> args = new HashMap<String, String>();
                Iterator<Map.Entry<String, Object>> it = configTokenizer
                    .iterator();
                // get className and args
                while (it.hasNext()) {
                  Map.Entry<String, Object> obj = it.next();
                  if (obj.getValue() instanceof String) {
                    if (obj.getKey().equals(FieldType.CLASS_NAME)) {
                      className = (String) obj.getValue();
                    } else {
                      args.put(obj.getKey(), (String) obj.getValue());
                    }
                  }
                }
                if (className != null) {
                  try {
                    Class<?> cls = Class.forName((String) className);
                    Class<?>[] types = { Map.class, SolrResourceLoader.class };
                    Constructor<?> cnstr = cls.getConstructor(types);
                    Object cff = cnstr.newInstance(args, resourceLoader);
                    if (cff instanceof MtasTokenizerFactory) {
                      config.fieldTypeTokenizerFactory.put(name,
                          (MtasTokenizerFactory) cff);
                    } else {
                      throw new IOException(
                          className + " is no MtasTokenizerFactory");
                    }
                  } catch (ClassNotFoundException | InstantiationException
                      | IllegalAccessException | IllegalArgumentException
                      | InvocationTargetException | NoSuchMethodException e) {
                    e.printStackTrace();
                    throw new IOException(e.getMessage());
                  }
                } else {
                  throw new IOException("no className");
                }
              }

            }
          }
        }
      }
      for (String field : fields.keySet()) {
        if (fields.get(field).getType() != null
            && config.fieldTypeTokenizerFactory
                .containsKey(fields.get(field).getType().getTypeName())) {
          config.fieldMapping.put(field,
              fields.get(field).getType().getTypeName());
        }
      }
    }
  }

  /* (non-Javadoc)
   * @see org.apache.solr.update.processor.UpdateRequestProcessorFactory#getInstance(org.apache.solr.request.SolrQueryRequest, org.apache.solr.response.SolrQueryResponse, org.apache.solr.update.processor.UpdateRequestProcessor)
   */
  @Override
  public UpdateRequestProcessor getInstance(SolrQueryRequest req,
      SolrQueryResponse rsp, UpdateRequestProcessor next) {
    try {
      init(req);
    } catch (IOException e) {
      e.printStackTrace();
    }
    return new MtasUpdateRequestProcessor(next, config);
  }

}

class MtasUpdateRequestProcessor extends UpdateRequestProcessor {

  private MtasUpdateRequestProcessorConfig config;

  public MtasUpdateRequestProcessor(UpdateRequestProcessor next,
      MtasUpdateRequestProcessorConfig config) {
    super(next);
    this.config = config;
  }

  @Override
  public void processAdd(AddUpdateCommand cmd) throws IOException {
    if (config != null && config.fieldMapping.size() > 0) {
      // get document
      SolrInputDocument doc = cmd.getSolrInputDocument();
      // loop over configurations
      for (String field : config.fieldMapping.keySet()) {
        SolrInputField originalValue = doc.get(field);
        String fieldType = config.fieldMapping.get(field);
        CharFilterFactory[] charFilterFactories = config.fieldTypeCharFilterFactories
            .get(fieldType);
        MtasTokenizerFactory tokenizerFactory = config.fieldTypeTokenizerFactory
            .get(config.fieldMapping.get(field));
        MtasUpdateRequestProcessorSizeReader sizeReader;
        if (originalValue != null) {
          // only string
          if (originalValue.getValue() instanceof String) {
            try {
              String storedValue = (String) originalValue.getValue();
              // create reader
              Reader reader = new StringReader(storedValue);
              // configuration
              String configuration = config.fieldTypeDefaultConfiguration
                  .get(fieldType);
              if (config.fieldTypeConfigurationFromField
                  .get(fieldType) != null) {
                Object obj = doc.getFieldValue(
                    config.fieldTypeConfigurationFromField.get(fieldType));
                if (obj != null) {
                  configuration = obj.toString();
                }
              }
              // charFilterFactories
              if (charFilterFactories != null) {
                for (CharFilterFactory charFilterFactory : charFilterFactories) {
                  if (charFilterFactory instanceof MtasCharFilterFactory) {
                    reader = ((MtasCharFilterFactory) charFilterFactory)
                        .create(reader, configuration);
                  } else {
                    reader = charFilterFactory.create(reader);
                  }
                  if (reader == null) {
                    throw new IOException(
                        "charFilter " + charFilterFactory.getClass().getName()
                            + " returns null");
                  }
                }
              }
              
              sizeReader = new MtasUpdateRequestProcessorSizeReader(reader);
              
              // tokenizerFactory
              MtasUpdateRequestProcessorResult result = new MtasUpdateRequestProcessorResult(
                  storedValue);
              MtasTokenizer tokenizer = tokenizerFactory.create(configuration);
              tokenizer.setReader(sizeReader);
              tokenizer.reset();
              // attributes
              CharTermAttribute termAttribute = tokenizer
                  .getAttribute(CharTermAttribute.class);
              OffsetAttribute offsetAttribute = tokenizer
                  .getAttribute(OffsetAttribute.class);
              PositionIncrementAttribute positionIncrementAttribute = tokenizer
                  .getAttribute(PositionIncrementAttribute.class);
              PayloadAttribute payloadAttribute = tokenizer
                  .getAttribute(PayloadAttribute.class);
              FlagsAttribute flagsAttribute = tokenizer
                  .getAttribute(FlagsAttribute.class);

              int numberOfPositions = 0;
              int numberOfTokens = 0;

              while (tokenizer.incrementToken()) {
                String term = null;
                Integer offsetStart = null, offsetEnd = null, posIncr = null,
                    flags = null;
                BytesRef payload = null;
                if (termAttribute != null) {
                  term = termAttribute.toString();
                }
                if (offsetAttribute != null) {
                  offsetStart = offsetAttribute.startOffset();
                  offsetEnd = offsetAttribute.endOffset();
                }
                if (positionIncrementAttribute != null) {
                  posIncr = positionIncrementAttribute.getPositionIncrement();
                }
                if (payloadAttribute != null) {
                  payload = payloadAttribute.getPayload();
                }
                if (flagsAttribute != null) {
                  flags = flagsAttribute.getFlags();
                }
                numberOfTokens++;
                numberOfPositions += posIncr;
                result.addItem(term, offsetStart, offsetEnd, posIncr, payload,
                    flags);
                // System.out.println(term);
              }

              // update field
              doc.remove(field);
              doc.addField(field,
                  MtasUpdateRequestProcessorResult.toString(result));
              // update size
              setFields(doc, config.fieldTypeSizeField.get(fieldType), sizeReader.getTotalReadSize());
              // update numberOfPositions
              setFields(doc,
                  config.fieldTypeNumberOfPositionsField.get(fieldType),
                  numberOfPositions);
              // update numberOfTokens
              setFields(doc, config.fieldTypeNumberOfTokensField.get(fieldType),
                  numberOfTokens);
            } catch (IOException e) {
              // update error
              doc.addField(config.fieldTypeErrorField.get(fieldType),
                  e.getMessage());
              // update size
              setFields(doc,
                  config.fieldTypeSizeField.get(fieldType), 0);
              // update numberOfPositions
              setFields(doc,
                  config.fieldTypeNumberOfPositionsField.get(fieldType), 0);
              // update numberOfTokens
              setFields(doc, config.fieldTypeNumberOfTokensField.get(fieldType),
                  0);
            }
          }
        }
      }

    }
    // pass it up the chain
    super.processAdd(cmd);
  }

  private void setFields(SolrInputDocument doc, String fieldNames,
      Object value) {
    if (fieldNames != null) {
      String[] tmpFields = fieldNames.split(",");
      for (int i = 0; i < tmpFields.length; i++) {
        if (!tmpFields[i].trim().equals("")) {
          doc.addField(tmpFields[i].trim(), value);
        }
      }
    }
  }

}

class MtasUpdateRequestProcessorConfig {

  HashMap<String, CharFilterFactory[]> fieldTypeCharFilterFactories;
  HashMap<String, MtasTokenizerFactory> fieldTypeTokenizerFactory;
  HashMap<String, String> fieldMapping;
  HashMap<String, String> fieldTypeDefaultConfiguration;
  HashMap<String, String> fieldTypeConfigurationFromField;
  HashMap<String, String> fieldTypeNumberOfTokensField;
  HashMap<String, String> fieldTypeNumberOfPositionsField;
  HashMap<String, String> fieldTypeSizeField;
  HashMap<String, String> fieldTypeErrorField;

  MtasUpdateRequestProcessorConfig() {
    fieldMapping = new HashMap<String, String>();
    fieldTypeCharFilterFactories = new HashMap<String, CharFilterFactory[]>();
    fieldTypeTokenizerFactory = new HashMap<String, MtasTokenizerFactory>();
    fieldTypeDefaultConfiguration = new HashMap<String, String>();
    fieldTypeConfigurationFromField = new HashMap<String, String>();
    fieldTypeNumberOfTokensField = new HashMap<String, String>();
    fieldTypeNumberOfPositionsField = new HashMap<String, String>();
    fieldTypeSizeField = new HashMap<String, String>();
    fieldTypeErrorField = new HashMap<String, String>();
  }

}

class MtasUpdateRequestProcessorSizeReader extends Reader {

  Reader reader;
  long totalReadSize;

  public MtasUpdateRequestProcessorSizeReader(Reader reader) {
    this.reader = reader;
    totalReadSize = 0;
  }

  public int read(char cbuf[], int off, int len) throws IOException {
    int read = reader.read(cbuf, off, len);
    totalReadSize += read;
    return read;
  }

  public void close() throws IOException {
    reader.close();
  }

  public long getTotalReadSize() {
    return totalReadSize;
  }

}
