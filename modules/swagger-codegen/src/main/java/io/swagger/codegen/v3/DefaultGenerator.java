package io.swagger.codegen.v3;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;

import io.swagger.codegen.v3.ignore.CodegenIgnoreProcessor;
import io.swagger.codegen.v3.templates.TemplateEngine;
import io.swagger.codegen.v3.utils.ImplementationVersion;
import io.swagger.codegen.v3.utils.URLPathUtil;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;

//import io.swagger.codegen.languages.AbstractJavaCodegen;

public class DefaultGenerator extends AbstractGenerator implements Generator {

    protected final Logger LOGGER = LoggerFactory.getLogger(DefaultGenerator.class);

    protected CodegenConfig config;

    protected ClientOptInput opts;

    protected OpenAPI openAPI;

    protected CodegenIgnoreProcessor ignoreProcessor;

    protected TemplateEngine templateEngine;

    private Boolean generateApis = null;

    private Boolean generateModels = null;

    private Boolean generateSupportingFiles = null;

    private Boolean generateApiTests = null;

    private Boolean generateApiDocumentation = null;

    private Boolean generateModelTests = null;

    private Boolean generateModelDocumentation = null;

    private Boolean generateSwaggerMetadata = true;

    private Boolean useOas2 = false;

    private String basePath;

    private String basePathWithoutHost;

    private String contextPath;

    private final Map<String, String> generatorPropertyDefaults = new HashMap<>();

    @Override
    public Generator opts(final ClientOptInput opts) {
        this.opts = opts;
        this.openAPI = opts.getOpenAPI();
        this.config = opts.getConfig();
        this.config.additionalProperties().putAll(opts.getOpts().getProperties());

        final String ignoreFileLocation = this.config.getIgnoreFilePathOverride();
        if (ignoreFileLocation != null) {
            final File ignoreFile = new File(ignoreFileLocation);
            if (ignoreFile.exists() && ignoreFile.canRead()) {
                this.ignoreProcessor = new CodegenIgnoreProcessor(ignoreFile);
            } else {
                this.LOGGER.warn(
                        "Ignore file specified at {} is not valid. This will fall back to an existing ignore file if present in the output directory.",
                        ignoreFileLocation);
            }
        }

        if (this.ignoreProcessor == null) {
            this.ignoreProcessor = new CodegenIgnoreProcessor(this.config.getOutputDir());
        }
        return this;
    }

    /**
     * Programmatically disable the output of .swagger-codegen/VERSION, .swagger-codegen-ignore, or other metadata files
     * used by Swagger Codegen.
     * @param generateSwaggerMetadata true: enable outputs, false: disable outputs
     */
    @SuppressWarnings("WeakerAccess")
    public void setGenerateSwaggerMetadata(final Boolean generateSwaggerMetadata) {
        this.generateSwaggerMetadata = generateSwaggerMetadata;
    }

    /**
     * Set generator properties otherwise pulled from system properties. Useful for running tests in parallel without
     * relying on System.properties.
     * @param key The system property key
     * @param value The system property value
     */
    @SuppressWarnings("WeakerAccess")
    public void setGeneratorPropertyDefault(final String key, final String value) {
        this.generatorPropertyDefaults.put(key, value);
    }

    private Boolean getGeneratorPropertyDefaultSwitch(final String key, final Boolean defaultValue) {
        String result = null;
        if (this.generatorPropertyDefaults.containsKey(key)) {
            result = this.generatorPropertyDefaults.get(key);
        }
        if (result != null) {
            return Boolean.valueOf(result);
        }
        return defaultValue;
    }

    private String getScheme() {
        String scheme = URLPathUtil.getScheme(this.openAPI, this.config);
        if (StringUtils.isBlank(scheme)) {
            scheme = "https";
        }
        return this.config.escapeText(scheme);
    }

    private void configureGeneratorProperties() {
        // allows generating only models by specifying a CSV of models to generate, or empty for all
        // NOTE: Boolean.TRUE is required below rather than `true` because of JVM boxing constraints and type inference.

        if (System.getProperty(CodegenConstants.GENERATE_APIS) != null) {
            this.generateApis = Boolean.valueOf(System.getProperty(CodegenConstants.GENERATE_APIS));
        } else {
            this.generateApis = System.getProperty(CodegenConstants.APIS) != null ? Boolean.TRUE
                    : this.getGeneratorPropertyDefaultSwitch(CodegenConstants.APIS, null);
        }
        if (System.getProperty(CodegenConstants.GENERATE_MODELS) != null) {
            this.generateModels = Boolean.valueOf(System.getProperty(CodegenConstants.GENERATE_MODELS));
        } else {
            this.generateModels = System.getProperty(CodegenConstants.MODELS) != null ? Boolean.TRUE
                    : this.getGeneratorPropertyDefaultSwitch(CodegenConstants.MODELS, null);
        }
        final String supportingFilesProperty = System.getProperty(CodegenConstants.SUPPORTING_FILES);
        if (("false".equalsIgnoreCase(supportingFilesProperty))) {
            this.generateSupportingFiles = false;
        } else {
            this.generateSupportingFiles = supportingFilesProperty != null ? Boolean.TRUE
                    : this.getGeneratorPropertyDefaultSwitch(CodegenConstants.SUPPORTING_FILES, null);
        }

        if (this.generateApis == null && this.generateModels == null && this.generateSupportingFiles == null) {
            // no specifics are set, generate everything
            this.generateApis = this.generateModels = this.generateSupportingFiles = true;
        } else {
            if (this.generateApis == null) {
                this.generateApis = false;
            }
            if (this.generateModels == null) {
                this.generateModels = false;
            }
            if (this.generateSupportingFiles == null) {
                this.generateSupportingFiles = false;
            }
        }
        // model/api tests and documentation options rely on parent generate options (api or model) and no other
        // options.
        // They default to true in all scenarios and can only be marked false explicitly
        Boolean generateModelTestsOption = this.getCustomOptionBooleanValue(CodegenConstants.MODEL_TESTS_OPTION);
        if (generateModelTestsOption == null) {
            generateModelTestsOption = System.getProperty(CodegenConstants.MODEL_TESTS) != null
                    ? Boolean.valueOf(System.getProperty(CodegenConstants.MODEL_TESTS))
                    : null;
        }
        Boolean generateModelDocsOption = this.getCustomOptionBooleanValue(CodegenConstants.MODEL_DOCS_OPTION);
        if (generateModelDocsOption == null) {
            generateModelDocsOption = System.getProperty(CodegenConstants.MODEL_DOCS) != null
                    ? Boolean.valueOf(System.getProperty(CodegenConstants.MODEL_DOCS))
                    : null;
        }
        Boolean generateAPITestsOption = this.getCustomOptionBooleanValue(CodegenConstants.API_TESTS_OPTION);
        if (generateAPITestsOption == null) {
            generateAPITestsOption = System.getProperty(CodegenConstants.API_TESTS) != null
                    ? Boolean.valueOf(System.getProperty(CodegenConstants.API_TESTS))
                    : null;
        }
        Boolean generateAPIDocsOption = this.getCustomOptionBooleanValue(CodegenConstants.API_DOCS_OPTION);
        if (generateAPIDocsOption == null) {
            generateAPIDocsOption = System.getProperty(CodegenConstants.API_DOCS) != null
                    ? Boolean.valueOf(System.getProperty(CodegenConstants.API_DOCS))
                    : null;
        }
        final Boolean useOas2Option = this.getCustomOptionBooleanValue(CodegenConstants.USE_OAS2_OPTION);

        this.generateModelTests = generateModelTestsOption != null ? generateModelTestsOption
                : this.getGeneratorPropertyDefaultSwitch(CodegenConstants.MODEL_TESTS, true);
        this.generateModelDocumentation = generateModelDocsOption != null ? generateModelDocsOption
                : this.getGeneratorPropertyDefaultSwitch(CodegenConstants.MODEL_DOCS, true);
        this.generateApiTests = generateAPITestsOption != null ? generateAPITestsOption
                : this.getGeneratorPropertyDefaultSwitch(CodegenConstants.API_TESTS, true);
        this.generateApiDocumentation = generateAPIDocsOption != null ? generateAPIDocsOption
                : this.getGeneratorPropertyDefaultSwitch(CodegenConstants.API_DOCS, true);
        this.useOas2 = useOas2Option != null ? useOas2Option
                : this.getGeneratorPropertyDefaultSwitch(CodegenConstants.USE_OAS2, false);

        // Additional properties added for tests to exclude references in project related files
        this.config.additionalProperties().put(CodegenConstants.GENERATE_API_TESTS, this.generateApiTests);
        this.config.additionalProperties().put(CodegenConstants.GENERATE_MODEL_TESTS, this.generateModelTests);

        this.config.additionalProperties().put(CodegenConstants.GENERATE_API_DOCS, this.generateApiDocumentation);
        this.config.additionalProperties().put(CodegenConstants.GENERATE_MODEL_DOCS, this.generateModelDocumentation);

        // Additional properties could be set already (f.e. using Maven plugin)
        if (useOas2Option != null && !this.config.additionalProperties().containsKey(CodegenConstants.USE_OAS2)) {
            this.config.additionalProperties().put(CodegenConstants.USE_OAS2, this.useOas2);
        }

        if (!this.generateApiTests && !this.generateModelTests) {
            this.config.additionalProperties().put(CodegenConstants.EXCLUDE_TESTS, true);
        }
        if (System.getProperty("debugSwagger") != null) {
            Json.prettyPrint(this.openAPI);
        }
        this.config.processOpts();
        this.config.preprocessOpenAPI(this.openAPI);
        this.config.additionalProperties().put("generatorVersion", ImplementationVersion.read());
        this.config.additionalProperties().put("generatedDate", ZonedDateTime.now().toString());
        this.config.additionalProperties().put("generatedYear", String.valueOf(ZonedDateTime.now().getYear()));
        this.config.additionalProperties().put("generatorClass", this.config.getClass().getName());
        this.config.additionalProperties().put("inputSpec", this.config.getInputSpec());
        if (this.openAPI.getExtensions() != null) {
            this.config.vendorExtensions().putAll(this.openAPI.getExtensions());
        }

        this.templateEngine = this.config.getTemplateEngine();

        final URL url = URLPathUtil.getServerURL(this.openAPI, this.config);

        this.contextPath = this.config.escapeText(url == null ? StringUtils.EMPTY : url.getPath());
        this.basePath = this.config.escapeText(URLPathUtil.getHost(this.openAPI));
        this.basePathWithoutHost = this.config.escapeText(this.contextPath);

    }

    private void configureSwaggerInfo() {
        final Info info = this.openAPI.getInfo();
        if (info == null) {
            return;
        }
        if (info.getTitle() != null) {
            this.config.additionalProperties().put("appName", this.config.escapeText(info.getTitle()));
        }
        if (info.getVersion() != null) {
            this.config.additionalProperties().put("appVersion", this.config.escapeText(info.getVersion()));
        } else {
            this.LOGGER.error("Missing required field info version. Default appVersion set to 1.0.0");
            this.config.additionalProperties().put("appVersion", "1.0.0");
        }

        if (StringUtils.isEmpty(info.getDescription())) {
            // set a default description if none is provided
            this.config.additionalProperties().put("appDescription",
                    "No description provided (generated by Swagger Codegen https://github.com/swagger-api/swagger-codegen)");
            this.config.additionalProperties().put("unescapedAppDescription",
                    "No description provided (generated by Swagger Codegen https://github.com/swagger-api/swagger-codegen)");
        } else {
            this.config.additionalProperties().put("appDescription", this.config.escapeText(info.getDescription()));
            this.config.additionalProperties().put("unescapedAppDescription", info.getDescription());
        }

        if (info.getContact() != null) {
            final Contact contact = info.getContact();
            if (contact.getEmail() != null) {
                this.config.additionalProperties().put("infoEmail", this.config.escapeText(contact.getEmail()));
            }
            if (contact.getName() != null) {
                this.config.additionalProperties().put("infoName", this.config.escapeText(contact.getName()));
            }
            if (contact.getUrl() != null) {
                this.config.additionalProperties().put("infoUrl", this.config.escapeText(contact.getUrl()));
            }
        }

        if (info.getLicense() != null) {
            final License license = info.getLicense();
            if (license.getName() != null) {
                this.config.additionalProperties().put("licenseInfo", this.config.escapeText(license.getName()));
            }
            if (license.getUrl() != null) {
                this.config.additionalProperties().put("licenseUrl", this.config.escapeText(license.getUrl()));
            }
        }

        if (info.getVersion() != null) {
            this.config.additionalProperties().put("version", this.config.escapeText(info.getVersion()));
        } else {
            this.LOGGER.error("Missing required field info version. Default version set to 1.0.0");
            this.config.additionalProperties().put("version", "1.0.0");
        }

        if (info.getTermsOfService() != null) {
            this.config.additionalProperties().put("termsOfService", this.config.escapeText(info.getTermsOfService()));
        }
    }

    private void generateModelTests(final List<File> files, final Map<String, Object> models, final String modelName)
            throws IOException {
        // to generate model test files
        for (final String templateName : this.config.modelTestTemplateFiles().keySet()) {
            final String suffix = this.config.modelTestTemplateFiles().get(templateName);
            final String filename = this.config.modelTestFileFolder() + File.separator
                    + this.config.toModelTestFilename(modelName) + suffix;
            // do not overwrite test file that already exists
            if (new File(filename).exists()) {
                this.LOGGER.info("File exists. Skipped overwriting " + filename);
                continue;
            }
            final File written = this.processTemplateToFile(models, templateName, filename);
            if (written != null) {
                files.add(written);
            }
        }
    }

    private void generateModelDocumentation(final List<File> files, final Map<String, Object> models,
            final String modelName) throws IOException {
        for (final String templateName : this.config.modelDocTemplateFiles().keySet()) {
            final String suffix = this.config.modelDocTemplateFiles().get(templateName);
            final String filename = this.config.modelDocFileFolder() + File.separator
                    + this.config.toModelDocFilename(modelName) + suffix;
            if (!this.config.shouldOverwrite(filename)) {
                this.LOGGER.info("Skipped overwriting " + filename);
                continue;
            }
            final File written = this.processTemplateToFile(models, templateName, filename);
            if (written != null) {
                files.add(written);
            }
        }
    }

    private void generateModels(final List<File> files, final List<Object> allModels) {

        if (!this.generateModels) {
            return;
        }

        final Map<String, Schema> schemas = this.openAPI.getComponents().getSchemas();
        if (schemas == null) {
            return;
        }

        final String modelNames = System.getProperty("models");
        Set<String> modelsToGenerate = null;
        if (modelNames != null && !modelNames.isEmpty()) {
            modelsToGenerate = new HashSet<>(Arrays.asList(modelNames.split(",")));
        }

        Set<String> modelKeys = schemas.keySet();
        if (modelsToGenerate != null && !modelsToGenerate.isEmpty()) {
            final Set<String> updatedKeys = new HashSet<>();
            for (final String m : modelKeys) {
                if (modelsToGenerate.contains(m)) {
                    updatedKeys.add(m);
                }
            }
            modelKeys = updatedKeys;
        }

        // store all processed models
        Map<String, Object> allProcessedModels = new TreeMap<>((o1, o2) -> ObjectUtils
                .compare(DefaultGenerator.this.config.toModelName(o1), DefaultGenerator.this.config.toModelName(o2)));

        // process models only
        for (final String name : modelKeys) {
            try {
                // don't generate models that have an import mapping
                if (!this.config.getIgnoreImportMapping() && this.config.importMapping().containsKey(name)) {
                    this.LOGGER.info("Model " + name + " not imported due to import mapping");
                    continue;
                }
                final Schema schema = schemas.get(name);
                final Map<String, Schema> schemaMap = new HashMap<>();
                schemaMap.put(name, schema);
                final Map<String, Object> models = this.processModels(this.config, schemaMap, schemas);
                models.put("classname", this.config.toModelName(name));
                models.putAll(this.config.additionalProperties());
                allProcessedModels.put(name, models);

                final List<Object> modelList = (List<Object>) models.get("models");

                if (modelList == null || modelList.isEmpty()) {
                    continue;
                }
            } catch (final Exception e) {
                throw new RuntimeException(
                        "Could not process model '" + name + "'" + ".Please make sure that your schema is correct!", e);
            }
        }

        final ISchemaHandler schemaHandler = this.config.getSchemaHandler();
        schemaHandler.readProcessedModels(allProcessedModels);

        final List<CodegenModel> composedModels = schemaHandler.getModels();

        if (composedModels != null && !composedModels.isEmpty()) {
            for (final CodegenModel composedModel : composedModels) {
                if (allProcessedModels.get(composedModel.name) != null) {
                    final Map<String, Object> models = (Map<String, Object>) allProcessedModels.get(composedModel.name);
                    models.put("x-is-composed-model", composedModel.isComposedModel);
                    continue;
                }
                final Map<String, Object> models = this.processModel(composedModel, this.config);
                models.put("classname", this.config.toModelName(composedModel.name));
                models.put("x-is-composed-model", composedModel.isComposedModel);
                models.putAll(this.config.additionalProperties());
                allProcessedModels.put(composedModel.name, models);
            }
        }

        // post process all processed models
        allProcessedModels = this.config.postProcessAllModels(allProcessedModels);

        // generate files based on processed models
        for (final String modelName : allProcessedModels.keySet()) {
            final Map<String, Object> models = (Map<String, Object>) allProcessedModels.get(modelName);
            try {
                // don't generate models that have an import mapping
                if (!this.config.getIgnoreImportMapping() && this.config.importMapping().containsKey(modelName)) {
                    continue;
                }
                final Map<String, Object> modelTemplate = (Map<String, Object>) ((List<Object>) models.get("models"))
                        .get(0);
                // Special handling of aliases only applies to Java
                if (this.config.checkAliasModel() && (modelTemplate != null && modelTemplate.containsKey("model"))) {
                    final CodegenModel codegenModel = (CodegenModel) modelTemplate.get("model");
                    final Map<String, Object> vendorExtensions = codegenModel.getVendorExtensions();
                    boolean isAlias = false;
                    if (vendorExtensions.get(CodegenConstants.IS_ALIAS_EXT_NAME) != null) {
                        isAlias = Boolean
                                .parseBoolean(vendorExtensions.get(CodegenConstants.IS_ALIAS_EXT_NAME).toString());
                    }
                    if (isAlias) {
                        continue; // Don't create user-defined classes for aliases
                    }
                }
                allModels.add(modelTemplate);
                for (final String templateName : this.config.modelTemplateFiles().keySet()) {
                    final String suffix = this.config.modelTemplateFiles().get(templateName);
                    final String filename = this.config.modelFileFolder() + File.separator
                            + this.config.toModelFilename(modelName) + suffix;
                    if (!this.config.shouldOverwrite(filename)) {
                        this.LOGGER.info("Skipped overwriting " + filename);
                        continue;
                    }
                    final File written = this.processTemplateToFile(models, templateName, filename);
                    if (written != null) {
                        files.add(written);
                    }
                }
                if (this.generateModelTests) {
                    this.generateModelTests(files, models, modelName);
                }
                if (this.generateModelDocumentation) {
                    // to generate model documentation files
                    this.generateModelDocumentation(files, models, modelName);
                }
            } catch (final Exception e) {
                throw new RuntimeException("Could not generate model '" + modelName + "'", e);
            }
        }
        if (System.getProperty("debugModels") != null) {
            this.LOGGER.info("############ Model info ############");
            Json.prettyPrint(allModels);
        }

    }

    private void generateApis(final List<File> files, final List<Object> allOperations, final List<Object> allModels) {
        if (!this.generateApis) {
            return;
        }
        boolean hasModel = true;
        if (allModels == null || allModels.isEmpty()) {
            hasModel = false;
        }

        if (this.openAPI.getPaths() == null) {
            return;
        }
        Map<String, List<CodegenOperation>> paths = this.processPaths(this.openAPI.getPaths());
        Set<String> apisToGenerate = null;
        final String apiNames = System.getProperty("apis");
        if (apiNames != null && !apiNames.isEmpty()) {
            apisToGenerate = new HashSet<>(Arrays.asList(apiNames.split(",")));
        }
        if (apisToGenerate != null && !apisToGenerate.isEmpty()) {
            final Map<String, List<CodegenOperation>> updatedPaths = new TreeMap<>();
            for (final String m : paths.keySet()) {
                if (apisToGenerate.contains(m)) {
                    updatedPaths.put(m, paths.get(m));
                }
            }
            paths = updatedPaths;
        }
        for (final String tag : paths.keySet()) {
            try {
                final List<CodegenOperation> ops = paths.get(tag);
                Collections.sort(ops, (one, another) -> ObjectUtils.compare(one.operationId, another.operationId));
                final Map<String, Object> operation = this.processOperations(this.config, tag, ops, allModels);

                this.processSecurityProperties(operation);

                operation.put("basePath", this.basePath);
                operation.put("basePathWithoutHost", this.basePathWithoutHost);
                operation.put("contextPath", this.contextPath);
                operation.put("baseName", tag);
                operation.put("modelPackage", this.config.modelPackage());
                operation.putAll(this.config.additionalProperties());
                operation.put("classname", this.config.toApiName(tag));
                operation.put("classVarName", this.config.toApiVarName(tag));
                operation.put("importPath", this.config.toApiImport(tag));
                operation.put("classFilename", this.config.toApiFilename(tag));

                if (!this.config.vendorExtensions().isEmpty()) {
                    operation.put("vendorExtensions", this.config.vendorExtensions());
                }

                // Pass sortParamsByRequiredFlag through to the Mustache template...
                boolean sortParamsByRequiredFlag = true;
                if (this.config.additionalProperties().containsKey(CodegenConstants.SORT_PARAMS_BY_REQUIRED_FLAG)) {
                    sortParamsByRequiredFlag = Boolean.parseBoolean(this.config.additionalProperties()
                            .get(CodegenConstants.SORT_PARAMS_BY_REQUIRED_FLAG).toString());
                }
                // this.LOGGER.info("SORTING ON TEMPLATE: " + sortParamsByRequiredFlag);
                operation.put("sortParamsByRequiredFlag", sortParamsByRequiredFlag);

                operation.put("hasModel", hasModel);

                allOperations.add(new HashMap<>(operation));
                for (int i = 0; i < allOperations.size(); i++) {
                    final Map<String, Object> oo = (Map<String, Object>) allOperations.get(i);
                    if (i < (allOperations.size() - 1)) {
                        oo.put("hasMore", "true");
                    }
                }

                for (final String templateName : this.config.apiTemplateFiles().keySet()) {
                    final String filename = this.config.apiFilename(templateName, tag);
                    if (!this.config.shouldOverwrite(filename) && new File(filename).exists()) {
                        this.LOGGER.info("Skipped overwriting " + filename);
                        continue;
                    }

                    final File written = this.processTemplateToFile(operation, templateName, filename);
                    if (written != null) {
                        files.add(written);
                    }
                }

                if (this.generateApiTests) {
                    // to generate api test files
                    for (final String templateName : this.config.apiTestTemplateFiles().keySet()) {
                        final String filename = this.config.apiTestFilename(templateName, tag);
                        // do not overwrite test file that already exists
                        if (new File(filename).exists()) {
                            this.LOGGER.info("File exists. Skipped overwriting " + filename);
                            continue;
                        }

                        final File written = this.processTemplateToFile(operation, templateName, filename);
                        if (written != null) {
                            files.add(written);
                        }
                    }
                }

                if (this.generateApiDocumentation) {
                    // to generate api documentation files
                    for (final String templateName : this.config.apiDocTemplateFiles().keySet()) {
                        final String filename = this.config.apiDocFilename(templateName, tag);
                        if (!this.config.shouldOverwrite(filename) && new File(filename).exists()) {
                            this.LOGGER.info("Skipped overwriting " + filename);
                            continue;
                        }

                        final File written = this.processTemplateToFile(operation, templateName, filename);
                        if (written != null) {
                            files.add(written);
                        }
                    }
                }

            } catch (final Exception e) {
                throw new RuntimeException("Could not generate api file for '" + tag + "'", e);
            }
        }
        if (System.getProperty("debugOperations") != null) {
            this.LOGGER.info("############ Operation info ############");
            Json.prettyPrint(allOperations);
        }

    }

    private void generateSupportingFiles(final List<File> files, final Map<String, Object> bundle) {
        if (!this.generateSupportingFiles) {
            return;
        }
        Set<String> supportingFilesToGenerate = null;
        final String supportingFiles = System.getProperty(CodegenConstants.SUPPORTING_FILES);
        boolean generateAll = false;
        if ("true".equalsIgnoreCase(supportingFiles)) {
            generateAll = true;
        } else if (supportingFiles != null && !supportingFiles.isEmpty()) {
            supportingFilesToGenerate = new HashSet<>(Arrays.asList(supportingFiles.split(",")));
        }

        for (final SupportingFile support : this.config.supportingFiles()) {
            try {
                String outputFolder = this.config.outputFolder();
                if (StringUtils.isNotEmpty(support.folder)) {
                    outputFolder += File.separator + support.folder;
                }
                final File of = new File(outputFolder);
                if (!of.isDirectory()) {
                    of.mkdirs();
                }
                final String outputFilename = outputFolder + File.separator
                        + support.destinationFilename.replace('/', File.separatorChar);
                if (!this.config.shouldOverwrite(outputFilename)) {
                    this.LOGGER.info("Skipped overwriting " + outputFilename);
                    continue;
                }
                String templateFile;
                if (support instanceof GlobalSupportingFile) {
                    templateFile = this.config.getCommonTemplateDir() + File.separator + support.templateFile;
                } else {
                    templateFile = this.getFullTemplateFile(this.config, support.templateFile);
                }

                boolean shouldGenerate = true;
                if (!generateAll && supportingFilesToGenerate != null && !supportingFilesToGenerate.isEmpty()) {
                    shouldGenerate = supportingFilesToGenerate.contains(support.destinationFilename);
                }
                if (!shouldGenerate) {
                    continue;
                }

                if (this.ignoreProcessor.allowsFile(new File(outputFilename))) {
                    if (templateFile.endsWith("mustache")) {
                        final String rendered = this.templateEngine.getRendered(templateFile, bundle);
                        this.writeToFile(outputFilename, rendered);
                        files.add(new File(outputFilename));
                    } else {
                        InputStream in = null;

                        try {
                            in = new FileInputStream(templateFile);
                        } catch (final Exception e) {
                            // continue
                        }
                        if (in == null) {
                            in = this.getClass().getClassLoader()
                                    .getResourceAsStream(this.getCPResourcePath(templateFile));
                        }
                        final File outputFile = new File(outputFilename);
                        final OutputStream out = new FileOutputStream(outputFile, false);
                        if (in != null) {
                            this.LOGGER.info("writing file " + outputFile);
                            IOUtils.copy(in, out);
                            out.close();
                        } else {
                            this.LOGGER.warn("can't open " + templateFile + " for input");
                        }
                        files.add(outputFile);
                    }
                } else {
                    this.LOGGER.info(
                            "Skipped generation of " + outputFilename + " due to rule in .swagger-codegen-ignore");
                }
            } catch (final Exception e) {
                throw new RuntimeException("Could not generate supporting file '" + support + "'", e);
            }
        }

        // Consider .swagger-codegen-ignore a supporting file
        // Output .swagger-codegen-ignore if it doesn't exist and wasn't explicitly created by a generator
        final String swaggerCodegenIgnore = ".swagger-codegen-ignore";
        final String ignoreFileNameTarget = this.config.outputFolder() + File.separator + swaggerCodegenIgnore;
        final File ignoreFile = new File(ignoreFileNameTarget);
        if (this.generateSwaggerMetadata && !ignoreFile.exists()) {
            final String ignoreFileNameSource = File.separator + this.config.getCommonTemplateDir() + File.separator
                    + swaggerCodegenIgnore;
            final String ignoreFileContents = this.readResourceContents(ignoreFileNameSource);
            try {
                this.writeToFile(ignoreFileNameTarget, ignoreFileContents);
            } catch (final IOException e) {
                throw new RuntimeException("Could not generate supporting file '" + swaggerCodegenIgnore + "'", e);
            }
            files.add(ignoreFile);
        }

        if (this.generateSwaggerMetadata) {
            final String swaggerVersionMetadata = this.config.outputFolder() + File.separator + ".swagger-codegen"
                    + File.separator + "VERSION";
            final File swaggerVersionMetadataFile = new File(swaggerVersionMetadata);
            try {
                this.writeToFile(swaggerVersionMetadata, ImplementationVersion.read());
                files.add(swaggerVersionMetadataFile);
            } catch (final IOException e) {
                throw new RuntimeException("Could not generate supporting file '" + swaggerVersionMetadata + "'", e);
            }
        }

        /*
         * The following code adds default LICENSE (Apache-2.0) for all generators To use license other than Apache2.0,
         * update the following file: modules/swagger-codegen/src/main/resources/_common/LICENSE final String
         * apache2License = "LICENSE"; String licenseFileNameTarget = config.outputFolder() + File.separator +
         * apache2License; File licenseFile = new File(licenseFileNameTarget); String licenseFileNameSource =
         * File.separator + config.getCommonTemplateDir() + File.separator + apache2License; String licenseFileContents
         * = readResourceContents(licenseFileNameSource); try { writeToFile(licenseFileNameTarget, licenseFileContents);
         * } catch (IOException e) { throw new RuntimeException("Could not generate LICENSE file '" + apache2License +
         * "'", e); } files.add(licenseFile);
         */

    }

    private Map<String, Object> buildSupportFileBundle(final List<Object> allOperations, final List<Object> allModels) {

        final Map<String, Object> bundle = new HashMap<>();
        bundle.putAll(this.config.additionalProperties());
        bundle.put("apiPackage", this.config.apiPackage());

        final Map<String, Object> apis = new HashMap<>();
        apis.put("apis", allOperations);

        final URL url = URLPathUtil.getServerURL(this.openAPI, this.config);

        if (url != null) {
            bundle.put("host", url.getHost());
        }

        bundle.put("openAPI", this.openAPI);
        bundle.put("basePath", this.basePath);
        bundle.put("basePathWithoutHost", this.basePathWithoutHost);
        bundle.put("scheme", URLPathUtil.getScheme(this.openAPI, this.config));
        bundle.put("contextPath", this.contextPath);
        bundle.put("apiInfo", apis);
        bundle.put("models", allModels);
        boolean hasModel = true;
        if (allModels == null || allModels.isEmpty()) {
            hasModel = false;
        }
        bundle.put("hasModel", hasModel);
        bundle.put("apiFolder", this.config.apiPackage().replace('.', File.separatorChar));
        bundle.put("modelPackage", this.config.modelPackage());

        this.processSecurityProperties(bundle);

        if (this.openAPI.getExternalDocs() != null) {
            bundle.put("externalDocs", this.openAPI.getExternalDocs());
        }
        for (int i = 0; i < allModels.size() - 1; i++) {
            final HashMap<String, CodegenModel> cm = (HashMap<String, CodegenModel>) allModels.get(i);
            final CodegenModel m = cm.get("model");
            m.getVendorExtensions().put(CodegenConstants.HAS_MORE_MODELS_EXT_NAME, Boolean.TRUE);
        }

        this.config.postProcessSupportingFileData(bundle);

        if (System.getProperty("debugSupportingFiles") != null) {
            this.LOGGER.info("############ Supporting file info ############");
            Json.prettyPrint(bundle);
        }
        return bundle;
    }

    @Override
    public List<File> generate() {

        if (this.openAPI == null) {
            throw new RuntimeException("missing OpenAPI input!");
        }
        if (this.config == null) {
            throw new RuntimeException("missing configuration input!");
        }
        this.configureGeneratorProperties();
        this.configureSwaggerInfo();

        final List<File> files = new ArrayList<>();
        // models
        final List<Object> allModels = new ArrayList<>();
        this.generateModels(files, allModels);
        // apis
        final List<Object> allOperations = new ArrayList<>();
        this.generateApis(files, allOperations, allModels);

        // supporting files
        final Map<String, Object> bundle = this.buildSupportFileBundle(allOperations, allModels);
        this.generateSupportingFiles(files, bundle);
        this.config.processOpenAPI(this.openAPI);
        return files;
    }

    @Override
    public String renderTemplate(final String template, final String context) {

        try {
            final Map<String, Object> bundle = new ObjectMapper().readValue(context, Map.class);
            final Handlebars handlebars = new Handlebars();
            final Template hTemplate = handlebars.compileInline(template);
            return hTemplate.apply(bundle);
        } catch (final IOException e) {
            final StringWriter sw = new StringWriter();
            final PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            return "Error rendering template: " + e.getMessage() + "\n" + sw.toString();
        }
    }

    @Override
    public Map<String, Object> generateBundle() {

        if (this.openAPI == null) {
            throw new RuntimeException("missing OpenAPI input!");
        }
        if (this.config == null) {
            throw new RuntimeException("missing configuration input!");
        }
        this.configureGeneratorProperties();
        this.configureSwaggerInfo();

        final List<File> files = new ArrayList<>();
        // models
        final List<Object> allModels = new ArrayList<>();
        this.generateModels(files, allModels);
        // apis
        final List<Object> allOperations = new ArrayList<>();
        this.generateApis(files, allOperations, allModels);

        // supporting files
        final Map<String, Object> bundle = this.buildSupportFileBundle(allOperations, allModels);
        Json.prettyPrint(bundle);
        this.generateSupportingFiles(files, bundle);
        this.config.processOpenAPI(this.openAPI);
        return bundle;
    }

    private File processTemplateToFile(final Map<String, Object> templateData, final String templateName,
            final String outputFilename) throws IOException {
        final String adjustedOutputFilename = outputFilename.replace("//", "/").replace('/', File.separatorChar);
        if (this.ignoreProcessor.allowsFile(new File(adjustedOutputFilename))) {
            final String templateFile = this.getFullTemplateFile(this.config, templateName);
            final String rendered = this.templateEngine.getRendered(templateFile, templateData);
            this.writeToFile(adjustedOutputFilename, rendered);
            return new File(adjustedOutputFilename);
        }

        this.LOGGER.info("Skipped generation of " + adjustedOutputFilename + " due to rule in .swagger-codegen-ignore");
        return null;
    }

    private static void processMimeTypes(final List<String> mimeTypeList, final Map<String, Object> operation,
            final String source) {
        if (mimeTypeList == null || mimeTypeList.isEmpty()) {
            return;
        }
        final List<Map<String, String>> c = new ArrayList<>();
        int count = 0;
        for (final String key : mimeTypeList) {
            final Map<String, String> mediaType = new HashMap<>();
            mediaType.put("mediaType", key);
            count += 1;
            if (count < mimeTypeList.size()) {
                mediaType.put("hasMore", "true");
            } else {
                mediaType.put("hasMore", null);
            }
            c.add(mediaType);
        }
        operation.put(source, c);
        final String flagFieldName = "has" + source.substring(0, 1).toUpperCase() + source.substring(1);
        operation.put(flagFieldName, true);

    }

    public Map<String, List<CodegenOperation>> processPaths(final Paths paths) {
        final Map<String, List<CodegenOperation>> ops = new TreeMap<>();
        for (final String resourcePath : paths.keySet()) {
            final PathItem path = paths.get(resourcePath);
            this.processOperation(resourcePath, "get", path.getGet(), ops, path);
            this.processOperation(resourcePath, "head", path.getHead(), ops, path);
            this.processOperation(resourcePath, "put", path.getPut(), ops, path);
            this.processOperation(resourcePath, "post", path.getPost(), ops, path);
            this.processOperation(resourcePath, "delete", path.getDelete(), ops, path);
            this.processOperation(resourcePath, "patch", path.getPatch(), ops, path);
            this.processOperation(resourcePath, "options", path.getOptions(), ops, path);
        }
        return ops;
    }

    private void processOperation(final String resourcePath, final String httpMethod, final Operation operation,
            final Map<String, List<CodegenOperation>> operations, final PathItem path) {
        if (operation == null) {
            return;
        }
        if (System.getProperty("debugOperations") != null) {
            this.LOGGER.info(
                    "processOperation: resourcePath= " + resourcePath + "\t;" + httpMethod + " " + operation + "\n");
        }
        final List<Tag> tags = new ArrayList<>();

        final List<String> tagNames = operation.getTags();
        final List<Tag> swaggerTags = this.openAPI.getTags();
        if (tagNames != null) {
            if (swaggerTags == null) {
                for (final String tagName : tagNames) {
                    tags.add(new Tag().name(tagName));
                }
            } else {
                for (final String tagName : tagNames) {
                    boolean foundTag = false;
                    for (final Tag tag : swaggerTags) {
                        if (tag.getName().equals(tagName)) {
                            tags.add(tag);
                            foundTag = true;
                            break;
                        }
                    }

                    if (!foundTag) {
                        tags.add(new Tag().name(tagName));
                    }
                }
            }
        }

        if (tags.isEmpty()) {
            tags.add(new Tag().name("default"));
        }

        /*
         * build up a set of parameter "ids" defined at the operation level per the swagger 2.0 spec
         * "A unique parameter is defined by a combination of a name and location" i'm assuming "location" == "in"
         */
        final Set<String> operationParameters = new HashSet<>();
        if (operation.getParameters() != null) {
            for (final Parameter parameter : operation.getParameters()) {
                operationParameters.add(generateParameterId(parameter));
            }
        }

        // need to propagate path level down to the operation
        if (path.getParameters() != null) {
            for (final Parameter parameter : path.getParameters()) {
                // skip propagation if a parameter with the same name is already defined at the operation level
                if (!operationParameters.contains(generateParameterId(parameter))
                        && operation.getParameters() != null) {
                    operation.getParameters().add(parameter);
                }
            }
        }

        final Map<String, Schema> schemas = this.openAPI.getComponents() != null
                ? this.openAPI.getComponents().getSchemas()
                : null;
        final Map<String, SecurityScheme> securitySchemes = this.openAPI.getComponents() != null
                ? this.openAPI.getComponents().getSecuritySchemes()
                : null;
        final List<SecurityRequirement> globalSecurities = this.openAPI.getSecurity();
        for (final Tag tag : tags) {
            try {
                final CodegenOperation codegenOperation = this.config.fromOperation(
                        this.config.escapeQuotationMark(resourcePath), httpMethod, operation, schemas, this.openAPI);
                codegenOperation.tags = new ArrayList<>(tags);
                this.config.addOperationToGroup(this.config.sanitizeTag(tag.getName()),
                        this.config.escapeQuotationMark(resourcePath), operation, codegenOperation, operations);

                final List<SecurityRequirement> securities = operation.getSecurity();
                if (securities != null && securities.isEmpty()) {
                    continue;
                }
                Map<String, SecurityScheme> authMethods = this.getAuthMethods(securities, securitySchemes);
                if (authMethods == null || authMethods.isEmpty()) {
                    authMethods = this.getAuthMethods(globalSecurities, securitySchemes);
                }

                if (authMethods != null && !authMethods.isEmpty()) {
                    codegenOperation.authMethods = this.config.fromSecurity(authMethods);
                    codegenOperation.getVendorExtensions().put(CodegenConstants.HAS_AUTH_METHODS_EXT_NAME,
                            Boolean.TRUE);
                }
            } catch (final Exception ex) {
                final String msg = "Could not process operation:\n" //
                        + "  Tag: " + tag + "\n"//
                        + "  Operation: " + operation.getOperationId() + "\n" //
                        + "  Resource: " + httpMethod + " " + resourcePath + "\n"//
                        // + " Definitions: " + swagger.getDefinitions() + "\n" //
                        + "  Exception: " + ex.getMessage();
                throw new RuntimeException(msg, ex);
            }
        }

    }

    private static String generateParameterId(final Parameter parameter) {
        return parameter.getName() + ":" + parameter.getIn();
    }

    private Map<String, Object> processOperations(final CodegenConfig config, final String tag,
            final List<CodegenOperation> ops, final List<Object> allModels) {
        final Map<String, Object> operations = new HashMap<>();
        final Map<String, Object> objs = new HashMap<>();
        objs.put("classname", config.toApiName(tag));
        objs.put("pathPrefix", config.toApiVarName(tag));

        // check for operationId uniqueness
        final Set<String> opIds = new HashSet<>();
        int counter = 0;
        for (final CodegenOperation op : ops) {
            final String opId = op.nickname;
            if (opIds.contains(opId)) {
                counter++;
                op.nickname += "_" + counter;
            }
            opIds.add(opId);
        }
        objs.put("operation", ops);

        operations.put("operations", objs);
        operations.put("package", config.apiPackage());

        final Set<String> allImports = new TreeSet<>();
        for (final CodegenOperation op : ops) {
            allImports.addAll(op.imports);
        }

        final List<Map<String, String>> imports = new ArrayList<>();
        for (final String nextImport : allImports) {
            final Map<String, String> im = new LinkedHashMap<>();
            String mapping = config.importMapping().get(nextImport);
            if (mapping == null) {
                mapping = config.toModelImport(nextImport);
            }
            if (mapping != null) {
                im.put("import", mapping);
                imports.add(im);
            }
        }

        operations.put("imports", imports);

        // add a flag to indicate whether there's any {{import}}
        if (imports.size() > 0) {
            operations.put("hasImport", true);
        }
        config.postProcessOperations(operations);
        config.postProcessOperationsWithModels(operations, allModels);
        if (objs.size() > 0) {
            final List<CodegenOperation> os = (List<CodegenOperation>) objs.get("operation");

            if (os != null && os.size() > 0) {
                final CodegenOperation op = os.get(os.size() - 1);
                op.getVendorExtensions().put(CodegenConstants.HAS_MORE_EXT_NAME, Boolean.FALSE);
            }
        }
        return operations;
    }

    private Map<String, Object> processModels(final CodegenConfig config, final Map<String, Schema> definitions,
            final Map<String, Schema> allDefinitions) {
        final Map<String, Object> objs = new HashMap<>();
        objs.put("package", config.modelPackage());
        final List<Object> models = new ArrayList<>();
        final Set<String> allImports = new LinkedHashSet<>();
        for (final String key : definitions.keySet()) {
            final Schema schema = definitions.get(key);
            final CodegenModel cm = config.fromModel(key, schema, allDefinitions);
            final Map<String, Object> mo = new HashMap<>();
            mo.put("model", cm);
            mo.put("schema", schema);
            mo.put("importPath", config.toModelImport(cm.classname));
            /**
             * if (cm.vendorExtensions.containsKey("oneOf-model")) { CodegenModel oneOfModel = (CodegenModel)
             * cm.vendorExtensions.get("oneOf-model"); mo.put("oneOf-model", oneOfModel); } if
             * (cm.vendorExtensions.containsKey("anyOf-model")) { CodegenModel anyOfModel = (CodegenModel)
             * cm.vendorExtensions.get("anyOf-model"); mo.put("anyOf-model", anyOfModel); }
             */
            models.add(mo);

            allImports.addAll(cm.imports);
        }
        objs.put("models", models);
        final Set<String> importSet = new TreeSet<>();
        for (final String nextImport : allImports) {
            String mapping = config.importMapping().get(nextImport);
            if (mapping == null) {
                mapping = config.toModelImport(nextImport);
            }
            if (mapping != null && !config.defaultIncludes().contains(mapping)) {
                importSet.add(mapping);
            }
            // add instantiation types
            mapping = config.instantiationTypes().get(nextImport);
            if (mapping != null && !config.defaultIncludes().contains(mapping)) {
                importSet.add(mapping);
            }
        }
        final List<Map<String, String>> imports = new ArrayList<>();
        for (final String s : importSet) {
            final Map<String, String> item = new HashMap<>();
            item.put("import", s);
            imports.add(item);
        }
        objs.put("imports", imports);
        config.postProcessModels(objs);
        return objs;
    }

    private Map<String, Object> processModel(final CodegenModel codegenModel, final CodegenConfig config) {
        final Map<String, Object> objs = new HashMap<>();
        objs.put("package", config.modelPackage());
        final List<Object> models = new ArrayList<>();

        final Map<String, Object> modelObject = new HashMap<>();
        modelObject.put("model", codegenModel);
        modelObject.put("importPath", config.toModelImport(codegenModel.classname));

        final Set<String> allImports = new LinkedHashSet<>();
        allImports.addAll(codegenModel.imports);
        models.add(modelObject);

        objs.put("models", models);
        final Set<String> importSet = new TreeSet<>();
        for (final String nextImport : allImports) {
            String mapping = config.importMapping().get(nextImport);
            if (mapping == null) {
                mapping = config.toModelImport(nextImport);
            }
            if (mapping != null && !config.defaultIncludes().contains(mapping)) {
                importSet.add(mapping);
            }
            // add instantiation types
            mapping = config.instantiationTypes().get(nextImport);
            if (mapping != null && !config.defaultIncludes().contains(mapping)) {
                importSet.add(mapping);
            }
        }
        final List<Map<String, String>> imports = new ArrayList<>();
        for (final String s : importSet) {
            final Map<String, String> item = new HashMap<>();
            item.put("import", s);
            imports.add(item);
        }
        objs.put("imports", imports);
        config.postProcessModels(objs);
        return objs;
    }

    private Map<String, SecurityScheme> getAuthMethods(final List<SecurityRequirement> securities,
            final Map<String, SecurityScheme> securitySchemes) {
        if (securities == null || (securitySchemes == null || securitySchemes.isEmpty())) {
            return null;
        }
        final Map<String, SecurityScheme> authMethods = new HashMap<>();
        for (final SecurityRequirement requirement : securities) {
            for (final String key : requirement.keySet()) {
                final SecurityScheme securityScheme = securitySchemes.get(key);
                if (securityScheme != null) {
                    authMethods.put(key, securityScheme);
                }
            }
        }
        return authMethods;
    }

    private Boolean getCustomOptionBooleanValue(final String option) {
        final List<CodegenArgument> languageArguments = this.config.getLanguageArguments();
        if (languageArguments == null || languageArguments.isEmpty()) {
            return null;
        }
        final Optional<CodegenArgument> optionalCodegenArgument = languageArguments.stream()
                .filter(argument -> option.equalsIgnoreCase(argument.getOption())).findFirst();

        if (!optionalCodegenArgument.isPresent()) {
            return null;
        }
        return Boolean.valueOf(optionalCodegenArgument.get().getValue());
    }

    protected void processSecurityProperties(final Map<String, Object> bundle) {
        final Map<String, SecurityScheme> securitySchemeMap = this.openAPI.getComponents() != null
                ? this.openAPI.getComponents().getSecuritySchemes()
                : null;
        final List<CodegenSecurity> authMethods = this.config.fromSecurity(securitySchemeMap);
        if (authMethods != null && !authMethods.isEmpty()) {
            bundle.put("authMethods", authMethods);
            bundle.put("hasAuthMethods", true);
        }
    }

}
