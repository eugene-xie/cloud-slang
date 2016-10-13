package io.cloudslang.lang.cli.utils;

import ch.lambdaj.function.convert.Converter;
import io.cloudslang.lang.api.Slang;
import io.cloudslang.lang.commons.services.api.SlangSourceService;
import io.cloudslang.lang.compiler.Extension;
import io.cloudslang.lang.compiler.SlangSource;
import io.cloudslang.lang.compiler.modeller.result.CompilationModellingResult;
import io.cloudslang.lang.entities.CompilationArtifact;
import io.cloudslang.lang.entities.SystemProperty;
import io.cloudslang.lang.entities.bindings.values.Value;
import io.cloudslang.lang.entities.utils.SetUtils;
import java.io.File;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.Validate;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import static ch.lambdaj.Lambda.convert;

/**
 * @author lesant
 * @version $Id$
 * @since 11/13/2014
 */
@Component
public class CompilerHelperImpl implements CompilerHelper {

    public static final String INVALID_DIRECTORY_ERROR_MESSAGE_SUFFIX = "' is not a directory";
    private static final Logger logger = Logger.getLogger(CompilerHelperImpl.class);
    private static final String SP_DIR = "properties";
    private static final String INPUT_DIR = "inputs";
    private static final String CONFIG_DIR = "configuration";
    private static final String DUPLICATE_SYSTEM_PROPERTY_ERROR_MESSAGE_PREFIX = "Duplicate system property: '";

    @Autowired
    private Slang slang;
    @Autowired
    private Yaml yaml;
    @Autowired
    private SlangSourceService slangSourceService;

    @Override
    public CompilationArtifact compile(String filePath, List<String> dependencies) {
        File file = getFile(filePath);

        try {
            return slang.compile(SlangSource.fromFile(file), getDependencySources(dependencies, file));
        } catch (Exception e) {
            handleException(file, e);
            return null;
        }
    }

    @Override
    public CompilationModellingResult compileSource(String filePath, List<String> dependencies) {
        File file = getFile(filePath);
        Set<SlangSource> dependencySources = getDependencySources(dependencies, file);
        return compileSource(file, dependencySources);
    }

    private CompilationModellingResult compileSource(File file, Set<SlangSource> dependencySources) {
        try {
            return slang.compileSource(SlangSource.fromFile(file), dependencySources);
        } catch (Exception e) {
            handleException(file, e);
            return null;
        }
    }

    private void handleException(File file, Exception e) {
        logger.error("Failed compilation for file : " + file.getName() + " ,Exception is : " + e.getMessage());
        throw new RuntimeException("Failed compilation for file : " + file.getName() + " ,Exception is : " + e.getMessage(), e);
    }

    @Override
    public List<CompilationModellingResult> compileFolders(List<String> foldersPaths) {
        List<CompilationModellingResult> results = new ArrayList<>();
        Set<SlangSource> dependencySources = getSourcesFromFolders(foldersPaths);
        for (SlangSource dependencySource : dependencySources) {
            File file = getFile(dependencySource.getFilePath());
            try {
                CompilationModellingResult result = slang.compileSource(dependencySource, dependencySources);
                result.setFile(file);
                results.add(result);
            } catch (Exception e) {
                logger.error("Failed compilation for file : " + file.getName() + " ,Exception is : " + e.getMessage());
            }
        }
        return results;
    }

    private File getFile(String filePath) {
        Validate.notNull(filePath, "File path can not be null");
        File file = new File(filePath);
        Validate.isTrue(file.isFile(), "File: " + file.getName() + " was not found");
        Extension.validateSlangFileExtension(file.getName());
        return file;
    }

    private Set<SlangSource> getDependencySources(List<String> dependencies, File file) {
        dependencies = getDependenciesIfEmpty(dependencies, file);
        Set<SlangSource> dependencySources = getSourcesFromFolders(dependencies);
        return dependencySources;
    }

    private Set<SlangSource> getSourcesFromFolders(List<String> dependencies) {
        Set<SlangSource> dependencySources = new HashSet<>();
        for (String dependency : dependencies) {
            Collection<File> dependenciesFiles = listSlangFiles(new File(dependency), true);
            for (File dependencyCandidate : dependenciesFiles) {
                SlangSource source = SlangSource.fromFile(dependencyCandidate);
                dependencySources.add(source);
            }
        }
        return dependencySources;
    }

    private List<String> getDependenciesIfEmpty(List<String> dependencies, File file) {
        if (CollectionUtils.isEmpty(dependencies)) {
            dependencies = new ArrayList<>();
            //app.home is the basedir property we set in our executables
            String appHome = System.getProperty("app.home", "");
            String contentRoot = appHome + File.separator + "content";
            File contentRootDir = new File(contentRoot);
            if (StringUtils.isNotEmpty(appHome) &&
                    contentRootDir.exists() && contentRootDir.isDirectory()) {
                dependencies.add(contentRoot);
            } else {
                //default behavior is taking the parent dir if not running from our executables
                dependencies.add(file.getParent());
            }
        }
        return dependencies;
    }

    @Override
    public Set<SystemProperty> loadSystemProperties(List<String> systemPropertyFiles) {
        String propertiesRelativePath = CONFIG_DIR + File.separator + SP_DIR;
        return loadPropertiesFromFiles(convertToFiles(systemPropertyFiles),
                Extension.getPropertiesFileExtensionValues(), propertiesRelativePath);
    }

    @Override
    public Map<String, Value> loadInputsFromFile(List<String> inputFiles) {
        String inputsRelativePath = CONFIG_DIR + File.separator + INPUT_DIR;
        return loadMapsFromFiles(convertToFiles(inputFiles), Extension.getYamlFileExtensionValues(), inputsRelativePath);
    }

    private Map<String, Value> loadMapsFromFiles(List<File> files, String[] extensions, String directory) {
        Collection<File> fileCollection;
        if (CollectionUtils.isEmpty(files)) {
            fileCollection = loadDefaultFiles(extensions, directory, false);
            if (CollectionUtils.isEmpty(fileCollection)) return null;
        } else {
            fileCollection = files;
        }
        Map<String, Value> result = new HashMap<>();
        for (File inputFile : fileCollection) {
            logger.info("Loading file: " + inputFile);
            try {
                String inputsFileContent = SlangSource.fromFile(inputFile).getContent();
                Boolean emptyContent = true;
                if (StringUtils.isNotEmpty(inputsFileContent)) {
                    @SuppressWarnings("unchecked") Map<String, ? extends Serializable> inputFileYamlContent =
                            (Map<String, ? extends Serializable>) yaml.load(inputsFileContent);
                    if (MapUtils.isNotEmpty(inputFileYamlContent)) {
                        emptyContent = false;
                        result.putAll(slangSourceService.convertInputFromMap(inputFileYamlContent, inputFile.getName()));
                    }
                }
                if (emptyContent) {
                    throw new RuntimeException("Inputs file: " + inputFile + " is empty or does not contain valid YAML content.");
                }
            } catch (RuntimeException ex) {
                logger.error("Error loading file: " + inputFile + ". Nested exception is: " + ex.getMessage(), ex);
                throw new RuntimeException(ex);
            }
        }
        return result;
    }

    private Set<SystemProperty> loadPropertiesFromFiles(List<File> files, String[] extensions, String directory) {
        Collection<File> fileCollection;
        if (CollectionUtils.isEmpty(files)) {
            fileCollection = loadDefaultFiles(extensions, directory, true);
            if (CollectionUtils.isEmpty(fileCollection)) return new HashSet<>();
        } else {
            fileCollection = files;
            for (File propertyFileCandidate : fileCollection) {
                Extension.validatePropertiesFileExtension(propertyFileCandidate.getName());
            }
        }
        Map<File, Set<SystemProperty>> loadedProperties = new HashMap<>();
        for (File propFile : fileCollection) {
            try {
                SlangSource source = SlangSource.fromFile(propFile);
                logger.info("Loading file: " + propFile);
                Set<SystemProperty> propsFromFile = slang.loadSystemProperties(source);
                mergeSystemProperties(loadedProperties, propsFromFile, propFile);
            } catch (Throwable ex) {
                String errorMessage = "Error loading file: " + propFile + " nested exception is " + ex.getMessage();
                logger.error(errorMessage, ex);
                throw new RuntimeException(errorMessage, ex);
            }
        }
        return SetUtils.mergeSets(loadedProperties.values());
    }

    private void mergeSystemProperties(
            Map<File, Set<SystemProperty>> target,
            Set<SystemProperty> propertiesFromFile,
            File sourceFile) {
        for (Map.Entry<File, Set<SystemProperty>> entry : target.entrySet()) {
            for (SystemProperty propertyFromFile : propertiesFromFile) {
                if (SetUtils.containsIgnoreCaseBasedOnFQN(entry.getValue(), propertyFromFile)) {
                    throw new RuntimeException(
                            DUPLICATE_SYSTEM_PROPERTY_ERROR_MESSAGE_PREFIX + propertyFromFile.getFullyQualifiedName() +
                                    "' in the following files: " + entry.getKey().getPath() + ", " + sourceFile.getPath()

                    );
                }
            }
        }
        target.put(sourceFile, propertiesFromFile);
    }

    private Collection<File> loadDefaultFiles(String[] extensions, String directory, boolean recursive) {
        Collection<File> files;
        String appHome = System.getProperty("app.home", "");
        String defaultDirectoryPath = appHome + File.separator + directory;
        File defaultDirectory = new File(defaultDirectoryPath);
        if (defaultDirectory.isDirectory()) {
            files = FileUtils.listFiles(defaultDirectory, extensions, recursive);
        } else {
            files = Collections.emptyList();
        }
        return files;
    }

    private List<File> convertToFiles(List<String> fileList) {
        return convert(fileList, new Converter<String, File>() {
            @Override
            public File convert(String from) {
                return new File(from);
            }
        });
    }

    // e.g. exclude .prop.sl from .sl set
    private Collection<File> listSlangFiles(File directory, boolean recursive) {
        Validate.isTrue(directory.isDirectory(), "Parameter '" + directory.getPath() + INVALID_DIRECTORY_ERROR_MESSAGE_SUFFIX);
        Collection<File> dependenciesFiles = FileUtils.listFiles(directory, Extension.getSlangFileExtensionValues(), recursive);
        Collection<File> result = new ArrayList<>();
        for (File file : dependenciesFiles) {
            if (Extension.SL.equals(Extension.findExtension(file.getName()))) {
                result.add(file);
            }
        }
        return result;
    }

}
