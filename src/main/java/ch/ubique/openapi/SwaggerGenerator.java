package ch.ubique.openapi;

import ch.ubique.openapi.wrapper.Documentation;
import ch.ubique.openapi.wrapper.JsonFormat;
import ch.ubique.openapi.wrapper.Mapping;
import ch.ubique.openapi.wrapper.PathVariable;
import ch.ubique.openapi.wrapper.RequestBody;
import ch.ubique.openapi.wrapper.RequestHeader;
import ch.ubique.openapi.wrapper.RequestMapping;
import ch.ubique.openapi.wrapper.RequestParam;

import edu.emory.mathcs.backport.java.util.Collections;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.javatuples.Pair;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMethod;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.representer.Representer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.lang.model.type.NullType;

/** Generate Swagger OpenAPI 3.0 yaml from springboot annotations */
@Mojo(
        name = "springboot-swagger-3",
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        requiresProject = true,
        defaultPhase = LifecyclePhase.COMPILE)
public class SwaggerGenerator extends AbstractMojo {
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    MavenProject project;

    @Parameter(defaultValue = "ch.ubique.viadi", required = true)
    String[] basePackages;

    @Parameter(defaultValue = "", required = false)
    String[] blackListedPackages;

    @Parameter(defaultValue = "", required = false)
    String[] ignoredTypes;

    @Parameter(required = true)
    String[] controllers;

    @Parameter(defaultValue = "generated/swagger/")
    String outPath;

    @Parameter(defaultValue = "swagger.yaml")
    String outFile;

    @Parameter String[] apiUrls;

    @Parameter(defaultValue = "1.0-SNAPSHOT")
    String apiVersion;

    @Parameter(defaultValue = "API definition generated with the maven plugin")
    String description;

    @Parameter(defaultValue = "SwaggerAPI")
    String title;

    private Map<String, Object> getInfoHeader() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("version", apiVersion);
        info.put("description", description);
        info.put("title", title);
        return info;
    }

    private List<Map<String, Object>> getServers() {
        List<Map<String, Object>> servers = new ArrayList<>();

        for (String url : apiUrls) {
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("url", url);
            server.put("description", "");
            servers.add(server);
        }
        return servers;
    }

    Map<String, Object> models = new TreeMap<>();

    private Map<String, Object> getModels() {
        return models;
    }

    private void addModel(Class<?> modelClass) {
        for (Map<String, Object> model : getModelDefinition("", modelClass)) {
            // we only have one key
            Set<String> keys = model.keySet();
            List<String> keysList = new ArrayList<>(keys);

            models.put(keysList.get(0), model.values().toArray()[0]);
        }
    }

    Map<String, Object> paths = new LinkedHashMap<>();

    private Map<String, Object> getPaths() {

        return paths;
    }

    private Map<String, Object> getRequestMethod(Method controllerMethod, RequestMapping wrapper) {
        Map<String, Object> method = new LinkedHashMap<>();
        method.put("summary", controllerMethod.getName());
        getLog().info("Check if annotation documentation is present");
        getLog().info(Arrays.toString(controllerMethod.getAnnotations()));
        if (controllerMethod.isAnnotationPresent(documentation)) {
            Documentation docWrapper =
                    new ch.ubique.openapi.wrapper.Documentation(
                            controllerMethod.getAnnotation(documentation));
            method.put("description", docWrapper.description());
        } else {
            method.put("description", controllerMethod.getName());
        }
        return method;
    }

    private Pair<Class<?>, Integer> resolveList(ParameterizedType type) {
        Class<?> innerClass = null;
        int nestedReturnValueLayer = 0;
        ParameterizedType listType = type;
        while (innerClass == null) {
            // try to load this class
            innerClass = loadClass(listType.getActualTypeArguments()[0].getTypeName());
            // if it is null, we have to get the inner list
            if (innerClass == null) {
                if (listType.getActualTypeArguments()[0] instanceof ParameterizedType) {

                    listType = (ParameterizedType) listType.getActualTypeArguments()[0];
                } else {
                    Type innerTmpClass = listType.getActualTypeArguments()[0];
                    if (innerTmpClass instanceof WildcardType) {
                        innerClass = Object.class;
                        getLog().warn("WildcardType don't allow for static Type extraction");
                        break;
                    }

                    innerClass = ((Class<?>) listType.getActualTypeArguments()[0]);
                    if (innerClass.isArray()) {
                        innerClass = innerClass.getComponentType();
                    }
                }
            }
            nestedReturnValueLayer++;
        }
        return new Pair<>(innerClass, nestedReturnValueLayer);
    }

    // get the first type which is not generic
    private Pair<Class<?>, Integer> getInnerClassFromGeneric(Class<?> container, Type type)
            throws Exception {
        Class<?> innerClass = null;
        int nestedReturnValueLayer = 0;

        // Check for generics of the form List<T>, Map<K,V> ResponseEntity<E> and so on
        if (type instanceof ParameterizedType) {
            // is the container a Collection type class
            if (Collection.class.isAssignableFrom(container)) {

                String typeName =
                        ((ParameterizedType) type).getActualTypeArguments()[0].getTypeName();
                innerClass = loadClass(typeName);
                // if the inner type is a list aswell innerClass will be null because we cannot
                // load generics with the class loader

                if (innerClass == null) {
                    Pair<Class<?>, Integer> innerStructure =
                            resolveList(
                                    (ParameterizedType)
                                            ((ParameterizedType) type).getActualTypeArguments()[0]);
                    innerClass = innerStructure.getValue0();
                    nestedReturnValueLayer = innerStructure.getValue1();
                }
            }
            // is the container a Map type class
            else if (Map.class.isAssignableFrom(container)) {
                // Get the key type
                String keytypeName =
                        ((ParameterizedType) type).getActualTypeArguments()[0].getTypeName();
                // get the value type
                String valuetypeName =
                        ((ParameterizedType) type).getActualTypeArguments()[1].getTypeName();

                Class<?> keyClass = loadClass(keytypeName);

                if (!isPrimitive(keyClass)) {
                    getLog().error("Maps need to have a primitive key type");
                    throw new Exception("Maps need to have a primitive key type");
                }
                // get the value type
                innerClass = loadClass(valuetypeName);
                if (innerClass == null) {
                    Pair<Class<?>, Integer> innerStructure = resolveList((ParameterizedType) type);
                    innerClass = innerStructure.getValue0();
                    nestedReturnValueLayer = innerStructure.getValue1();
                }
            }
            // the container is a generic like ResponseEntity<E>
            else {
                // get the inner type and load its class
                String typeName =
                        ((ParameterizedType) type).getActualTypeArguments()[0].getTypeName();
                innerClass = loadClass(typeName);

                if (innerClass == null) {
                    Pair<Class<?>, Integer> innerStructure = resolveList((ParameterizedType) type);
                    innerClass = innerStructure.getValue0();
                    // since we are ourselves in a Parameterized type we get one too many for the
                    // inner
                    // structure
                    nestedReturnValueLayer = innerStructure.getValue1() - 1;
                }
            }

        }
        // we have an array of the form boolean[]
        else if (type instanceof GenericArrayType) {
            // get typename and loadClass
            String typeName = ((GenericArrayType) type).getGenericComponentType().getTypeName();
            innerClass = loadClass(typeName);
        } else {
            // this is not a generic type return the container class
            innerClass = container;
        }
        return new Pair<>(innerClass, nestedReturnValueLayer);
    }

    private boolean hasAcceptHeader(List<String> headers) {
        for (String str : headers) {
            if (str.contains("Accept=")) {
                return true;
            }
        }
        return false;
    }

    private String[] getMimeTypes(List<String> headers) {
        List<String> strings = new ArrayList<>();
        for (String str : headers) {
            if (str.contains("Accept=")) {
                String mimes = str.replace("Accept=", "");
                Collections.addAll(strings, mimes.split(","));
            }
        }
        String[] returnValue = new String[strings.size()];
        return strings.toArray(returnValue);
    }

    private Map<String, Object> getResponseContent(
            Class<?> returnType, RequestMapping wrapper, int nestedReturnValueLayer) {
        LinkedHashMap<String, Object> content = new LinkedHashMap<>();
        // default response product is application/json
        String product = "";
        List<String> headers = new ArrayList<>();
        Collections.addAll(headers, wrapper.headers());

        if (wrapper.produces().length != 0) {
            product = wrapper.produces()[0];
        } else if (hasAcceptHeader(headers)) {
            // get first
            product = getMimeTypes(headers)[0];
        } else if (returnType == byte.class) {
            product = "application/octet-stream";
        } else {
            product = "application/json";
        }
        content.put(product, new LinkedHashMap<String, Object>());

        Map<String, Object> contentType = (Map<String, Object>) content.get(product);
        Map<String, Object> tmpSchema = new LinkedHashMap<>();
        contentType.put("schema", tmpSchema);
        if (isPrimitive(returnType)) {
            // we have a primitive return type
            mapPrimitiveTypeAndFormat(tmpSchema, returnType.getSimpleName());
        } else if (product.equals("application/octet-stream") || returnType == byte.class) {
            // we have a binary content
            tmpSchema.put("type", "string");
            tmpSchema.put("format", "binary");
        } else {
            // we have a json response

            // we have an array
            // if we have an array we need to add respective definitions
            for (int i = 0; i < nestedReturnValueLayer; i++) {
                tmpSchema.put("type", "array");
                tmpSchema.put("items", new LinkedHashMap<String, Object>());
                tmpSchema = (Map<String, Object>) tmpSchema.get("items");
            }
            tmpSchema.put("$ref", "#/components/schemas/" + returnType.getCanonicalName());
        }
        return content;
    }

    private void addPathsForController(Class<?> controller) throws Exception {
        String baseUrl = "";
        RequestMapping rm = new RequestMapping(getRequestMapping(controller));
        if (rm.value().length > 0) {
            baseUrl += rm.value()[0];
        }

        // theMethods.sort(c);
        for (Method controllerMethod : getDeclaredMethodsInOrder(controller)) {
            // skip all methods which do not have a requestMapping annotation
            if (!controllerMethod.isAnnotationPresent(requestMapping)
                    && !controllerMethod.isAnnotationPresent(getMapping)
                    && !controllerMethod.isAnnotationPresent(postMapping)) continue;

            // Get a wrapper arround the Annotation

            RequestMapping wrapper =
                    new RequestMapping(getRequestMappingForMethod(controllerMethod));
            // the controller method has a dictionar of httpreturncode:text
            Documentation docWrapper = null;
            Map<String, String> returnCodeToDescription = new LinkedHashMap<>();

            if (controllerMethod.isAnnotationPresent(documentation)) {
                docWrapper = new Documentation(controllerMethod.getAnnotation(documentation));
                if (docWrapper.undocumented()) {
                    continue;
                }
                String[] returnCodes = docWrapper.responses();
                for (String response : returnCodes) {
                    String[] codeToDesc = response.split("=>");
                    if (codeToDesc.length == 2) {
                        returnCodeToDescription.put(codeToDesc[0].trim(), codeToDesc[1].trim());
                    }
                }
            } else {
                getLog().warn("No Documentation annotation found");
            }
            // Add baseUrl to the path

            String path =
                    wrapper.value().length > 0
                            ? wrapper.value()[0]
                            : wrapper.path().length > 0 ? wrapper.path()[0] : "";
            getLog().warn(path);
            if (!path.startsWith("/")) {
                path = baseUrl + "/" + path;
            } else {
                path = baseUrl + path;
            }

            // We have a request so create request object
            Map<String, Object> request =
                    (Map<String, Object>) paths.computeIfAbsent(path, (x) -> new LinkedHashMap<>());

            // get the generic Return Type (e.g ResponseBody<T> List<T> Map<K,V> and so on)
            Type returnType = controllerMethod.getGenericReturnType();

            // The request method, default is get

            String theMethod = "get";
            getLog().error(wrapper.annotationType().toString());
            if (wrapper.method().length > 0) {
                theMethod = wrapper.method()[0].toString().toLowerCase();
            }

            Map<String, Object> method =
                    (Map<String, Object>)
                            request.computeIfAbsent(
                                    theMethod, (x) -> getRequestMethod(controllerMethod, wrapper));

            // 0 means the object is directly the return value; 1 means one list, 2 two ....
            int nestedReturnValueLayer = 0;

            // get class fallback (if it is not a generic)
            Class<?> actualClass = controllerMethod.getReturnType();

            // extract inner structure
            Pair<Class<?>, Integer> innerStructure =
                    getInnerClassFromGeneric(actualClass, returnType);
            actualClass = innerStructure.getValue0();
            nestedReturnValueLayer = innerStructure.getValue1();

            // Start setting up response
            Map<String, Object> responses =
                    (Map<String, Object>)
                            method.computeIfAbsent("responses", (x) -> new LinkedHashMap<>());
            if (returnCodeToDescription.isEmpty()) {
                String statusCode = "200";
                Map<String, Object> statusCodeMap =
                        (Map<String, Object>)
                                responses.computeIfAbsent(statusCode, (x) -> new LinkedHashMap<>());
                if (returnCodeToDescription.containsKey(statusCode)
                        && !statusCodeMap.containsKey("description")) {
                    statusCodeMap.put("description", returnCodeToDescription.get(statusCode));
                } else {
                    statusCodeMap.put("description", "");
                }

                // only if response type is not void do we need a contetnt
                if (statusCode.contains("200")
                        && actualClass != Void.class
                        && !actualClass.getSimpleName().toLowerCase().equals("void")) {
                    if (statusCodeMap.containsKey("content")) {
                        Map<String, Object> innerMap =
                                (LinkedHashMap<String, Object>) statusCodeMap.get("content");
                        innerMap.putAll(
                                getResponseContent(actualClass, wrapper, nestedReturnValueLayer));
                    } else {
                        statusCodeMap.put(
                                "content",
                                getResponseContent(actualClass, wrapper, nestedReturnValueLayer));
                    }
                }
            }
            for (String statusCode : returnCodeToDescription.keySet()) {
                Map<String, Object> statusCodeMap = new LinkedHashMap<>();
                responses.put(statusCode, statusCodeMap);
                statusCodeMap.put(
                        "description", returnCodeToDescription.getOrDefault(statusCode, ""));

                // only if response type is not void do we need a content
                if (statusCode.contains("200")
                        && actualClass != Void.class
                        && !actualClass.getSimpleName().toLowerCase().equals("void")) {
                    statusCodeMap.put(
                            "content",
                            getResponseContent(actualClass, wrapper, nestedReturnValueLayer));
                }
                // add the return type as a model to the schemas definition (and all inner types)

            }
            addModel(actualClass);
            // add parameters to request
            List<Map<String, Object>> parameters = getParameters(controllerMethod, wrapper, method);
            if (parameters.size() > 0) {
                method.put("parameters", parameters);
            }
        }
    }

    private Map<String, Object> getRequestParam(java.lang.reflect.Parameter obj) {
        RequestParam wrap = new RequestParam(obj.getAnnotation(requestParam));

        String fieldName = (wrap.value().isEmpty() ? obj.getName() : wrap.value());
        Map<String, Object> param = new LinkedHashMap<>();
        Map<String, Object> schema = new LinkedHashMap<>();
        param.put("name", fieldName);
        param.put("in", "query");
        if (obj.getAnnotation(documentation) != null) {
            Documentation docWrapper = new Documentation(obj.getAnnotation(documentation));
            param.put("description", docWrapper.description());
            param.put("example", docWrapper.example());
        } else {
            param.put("description", wrap.name());
        }
        param.put("required", wrap.required());
        param.put("schema", schema);
        if (isPrimitive(obj.getType())) {
            mapPrimitiveTypeAndFormat(schema, obj.getType().getSimpleName());
        } else {
            // we need to check for a list
            if (Collection.class.isAssignableFrom(obj.getType())) {
                schema.put("type", "array");
                schema.put("items", new LinkedHashMap<String, Object>());
                String innerName =
                        ((ParameterizedType) obj.getParameterizedType())
                                .getActualTypeArguments()[0].getTypeName();
                Class<?> inner = loadClass(innerName);
                if (isPrimitive(inner)) {
                    Map<String, Object> itemsMap = ((Map<String, Object>) schema.get("items"));
                    mapPrimitiveTypeAndFormat(itemsMap, inner.getSimpleName());
                } else {
                    ((Map<String, Object>) schema.get("items"))
                            .put("$ref", inner.getCanonicalName());
                    // add all model defintions occuring the the schemas
                    addModel(inner);
                }
            } else {
                schema.put("$ref", "#/components/schemas/" + obj.getType().getCanonicalName());
                // add all model defintions occuring the the schemas
                addModel(obj.getType());
            }
        }
        return param;
    }

    private Map<String, Object> getPathParam(java.lang.reflect.Parameter obj) {
        PathVariable wrap = new PathVariable(obj.getAnnotation(pathVariable));

        String fieldName = (wrap.value().isEmpty() ? obj.getName() : wrap.value());
        Map<String, Object> param = new LinkedHashMap<>();
        Map<String, Object> schema = new LinkedHashMap<>();
        param.put("name", fieldName);
        param.put("in", "path");
        if (obj.getAnnotation(documentation) != null) {
            Documentation docWrapper = new Documentation(obj.getAnnotation(documentation));
            param.put("description", docWrapper.description());
            param.put("example", docWrapper.example());
        } else {
            param.put("description", wrap.name());
        }
        param.put("required", wrap.required());
        param.put("schema", schema);
        if (isPrimitive(obj.getType())) {
            mapPrimitiveTypeAndFormat(schema, obj.getType().getSimpleName());
        } else {
            // we need to check for a list
            if (Collection.class.isAssignableFrom(obj.getType())) {
                schema.put("type", "array");
                schema.put("items", new LinkedHashMap<String, Object>());
                String innerName =
                        ((ParameterizedType) obj.getParameterizedType())
                                .getActualTypeArguments()[0].getTypeName();
                Class<?> inner = loadClass(innerName);
                if (isPrimitive(inner)) {
                    Map<String, Object> itemsMap = ((Map<String, Object>) schema.get("items"));
                    mapPrimitiveTypeAndFormat(itemsMap, inner.getSimpleName());
                } else {
                    ((Map<String, Object>) schema.get("items"))
                            .put("$ref", inner.getCanonicalName());
                    // add all model defintions occuring the the schemas
                    addModel(inner);
                }
            } else {
                schema.put("$ref", "#/components/schemas/" + obj.getType().getCanonicalName());
                // add all model defintions occuring the the schemas
                addModel(obj.getType());
            }
        }
        return param;
    }

    private Map<String, Object> getRequestHeader(java.lang.reflect.Parameter obj) {
        RequestHeader wrap = new RequestHeader(obj.getAnnotation(requestHeader));
        String fieldName = (wrap.value().isEmpty() ? obj.getName() : wrap.value());
        Map<String, Object> param = new LinkedHashMap<>();
        Map<String, Object> schema = new LinkedHashMap<>();
        param.put("name", fieldName);
        param.put("in", "header");
        if (obj.getAnnotation(documentation) != null) {
            Documentation docWrapper = new Documentation(obj.getAnnotation(documentation));
            param.put("description", docWrapper.description());
            param.put("example", docWrapper.example());
        } else {
            param.put("description", wrap.name());
        }
        param.put("required", wrap.required());
        param.put("schema", schema);
        if (isPrimitive(obj.getType())) {
            mapPrimitiveTypeAndFormat(schema, obj.getType().getSimpleName());
        } else {
            schema.put("$ref", "#/components/schemas/" + obj.getType().getCanonicalName());
            addModel(obj.getType());
        }
        return param;
    }

    private Map<String, Object> getRequestBody(
            java.lang.reflect.Parameter obj, RequestMapping wrapper) {
        String consumes = "";
        if (wrapper.consumes().length > 0) {
            consumes = wrapper.consumes()[0];
        } else {
            consumes = "application/json";
        }
        String fieldName = obj.getName();
        Map<String, Object> param = new LinkedHashMap<>();
        Map<String, Object> schema = new LinkedHashMap<>();
        // we have a requestbody we need to conform to OpenAPI requestBody
        if (obj.isAnnotationPresent(requestBody)) {
            RequestBody wrap = new RequestBody(obj.getAnnotation(requestBody));
            param.put("required", wrap.required());
        }

        param.put("content", new LinkedHashMap<String, Object>());
        if (obj.getAnnotation(documentation) != null) {
            Documentation docWrapper = new Documentation(obj.getAnnotation(documentation));
            param.put("description", docWrapper.description());
        } else {
            param.put("description", "N/A");
        }
        Map<String, Object> bodyContent = (LinkedHashMap<String, Object>) param.get("content");
        bodyContent.put(consumes, new LinkedHashMap<String, Object>());
        ((LinkedHashMap<String, Object>) bodyContent.get(consumes)).put("schema", schema);
        if (isPrimitive(obj.getType())) {
            schema.put("type", javaToSwaggerLinkedHashMap.get(obj.getType().getSimpleName()));
        } else {
            schema.put("$ref", "#/components/schemas/" + obj.getType().getCanonicalName());
            addModel(obj.getType());
        }
        return param;
    }

    // extract paramertes from function
    private List<Map<String, Object>> getParameters(
            Method controllerMethod, RequestMapping wrapper, Map<String, Object> methodMap) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        if (controllerMethod.getParameterCount() > 0) {
            for (java.lang.reflect.Parameter obj : controllerMethod.getParameters()) {
                Map<String, Object> param = null;
                // check if it is requestparam
                if (obj.getAnnotation(documentation) != null) {
                    Documentation docWrapper = new Documentation(obj.getAnnotation(documentation));
                    if (docWrapper.undocumented()) {
                        continue;
                    }
                }
                if (obj.getAnnotation(pathVariable) != null) {
                    param = getPathParam(obj);
                }
                if (obj.getAnnotation(requestParam) != null) {
                    param = getRequestParam(obj);
                }
                // check for request header
                else if (obj.getAnnotation(requestHeader) != null) {
                    param = getRequestHeader(obj);
                }
                // check for request body
                else if (obj.getAnnotation(requestBody) != null) {
                    methodMap.put("requestBody", getRequestBody(obj, wrapper));
                } else if (obj.getAnnotation(modelAttribute) != null) {
                    methodMap.put("requestBody", getRequestBody(obj, wrapper));
                }
                if (param != null) {
                    parameters.add(param);
                }
            }
        }
        return parameters;
    }

    @SuppressWarnings("unchecked")
    public void execute() throws MojoExecutionException {
        Log logger = getLog();
        logger.info("Get all Classes with a @Controller annotation");
        List<Class<?>> controllerClasses = getControllerClasses();
        // swagger map
        Map<String, Object> swagger = new LinkedHashMap<>();

        swagger.put("openapi", "3.0.0");
        swagger.put("servers", getServers());
        swagger.put("info", getInfoHeader());
        swagger.put("paths", getPaths());
        swagger.put("components", new LinkedHashMap<String, Object>());
        ((LinkedHashMap<String, Object>) swagger.get("components")).put("schemas", getModels());

        for (Class<?> controller : controllerClasses) {
            logger.info("Found controller: " + controller.getSimpleName());
            try {
                addPathsForController(controller);
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        logger.info("All paths added, start writing yaml");
        writeYaml(swagger);
        // logger.warn("JUST SOME TEST");
    }

    private void writeYaml(Map<String, Object> swagger) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        CustomPropertyUtils customPropertyUtils = new CustomPropertyUtils();
        Representer customRepresenter = new Representer();
        customRepresenter.setPropertyUtils(customPropertyUtils);
        Yaml yaml = new Yaml(customRepresenter, options);

        FileWriter writer;
        try {

            File directory = new File(project.getBasedir(), outPath);
            directory.mkdirs();
            File file = new File(directory, outFile);
            file.createNewFile();
            writer = new FileWriter(file.getAbsolutePath());

            yaml.dump(swagger, writer);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private Mapping getRequestMapping(Class<?> controllerClass) {
        Log logger = getLog();
        Object obj = null;
        RequestMethod method = RequestMethod.GET;
        if (controllerClass.isAnnotationPresent(requestMapping)) {
            obj = controllerClass.getAnnotation(requestMapping);
            try {
                method =
                        Enum.valueOf(
                                RequestMethod.class,
                                ((Object[])
                                                obj.getClass()
                                                        .getMethod("method", null)
                                                        .invoke(obj, null))
                                        [0].toString());
            } catch (Exception ex) {
                method = RequestMethod.GET;
            }
        }
        if (controllerClass.isAnnotationPresent(getMapping)) {
            obj = controllerClass.getAnnotation(getMapping);
            method = RequestMethod.GET;
        }
        if (controllerClass.isAnnotationPresent(postMapping)) {
            obj = controllerClass.getAnnotation(postMapping);
            method = RequestMethod.POST;
        }
        logger.error(obj.toString());
        return new Mapping(obj, method);
    }

    private Mapping getRequestMappingForMethod(Method controllerClass) {
        Log logger = getLog();
        Object obj = null;
        RequestMethod method = RequestMethod.GET;
        if (controllerClass.isAnnotationPresent(requestMapping)) {
            obj = controllerClass.getAnnotation(requestMapping);
            try {
                method =
                        Enum.valueOf(
                                RequestMethod.class,
                                ((Object[])
                                                obj.getClass()
                                                        .getMethod("method", null)
                                                        .invoke(obj, null))
                                        [0].toString());
            } catch (Exception ex) {
                method = RequestMethod.GET;
            }
        }
        if (controllerClass.isAnnotationPresent(getMapping)) {
            obj = controllerClass.getAnnotation(getMapping);
            method = RequestMethod.GET;
        }
        if (controllerClass.isAnnotationPresent(postMapping)) {
            obj = controllerClass.getAnnotation(postMapping);
            method = RequestMethod.POST;
        }
        logger.error(obj.toString());
        return new Mapping(obj, method);
    }

    private Controller getControllerMapping(Class<?> controllerClass) {
        return (Controller) controllerClass.getAnnotation(controller);
    }

    private Class<? extends Annotation> requestMapping;
    private Class<? extends Annotation> controller;
    private Class<? extends Annotation> requestParam;
    private Class<? extends Annotation> responseBody;
    private Class<? extends Annotation> requestHeader;
    private Class<? extends Annotation> modelAttribute;
    private Class<? extends Annotation> requestBody;
    private Class<? extends Annotation> jsonIgnore;
    private Class<? extends Annotation> notNull;
    private Class<? extends Annotation> jsonRawValue;
    private Class<? extends Annotation> pathVariable;
    private Class<? extends Annotation> documentation;
    private Class<? extends Annotation> jsonFormat;
    public static Class<? extends Annotation> getMapping;
    public static Class<? extends Annotation> postMapping;

    private Class<?> loadClass(String name) {
        try {
            return loader.loadClass(name);
        } catch (ClassNotFoundException e) {
            // don't print stacktrace since we rely on this value being null e.g. for generics
        }
        return null;
    }

    private URLClassLoader loader;

    private List<Class<?>> getControllerClasses() throws MojoExecutionException {
        List<Class<?>> requestClasses = new ArrayList<>();
        List<String> classpathElements = null;
        try {
            classpathElements = project.getCompileClasspathElements();

            List<URL> projectClasspathList = new ArrayList<>();
            for (String element : classpathElements) {
                if (element.contains(project.getBasedir().getAbsolutePath())) {
                    // try adding kotlin sourcepath
                    getLog().info("try adding kotlin path to: " + element);
                    String root = element.replace("classes", "kotlin-ic/compile/classes");
                    try {
                        projectClasspathList.add(new File(root).toURI().toURL());
                        getLog().info("Found Kotlin classpath: " + root);
                    } catch (MalformedURLException e) {
                        getLog().warn("No Kotlin class path found");
                        e.printStackTrace();
                    }
                }
                try {
                    projectClasspathList.add(new File(element).toURI().toURL());
                } catch (MalformedURLException e) {
                    throw new MojoExecutionException(
                            element + " is an invalid classpath element", e);
                }
            }

            loader = new URLClassLoader(projectClasspathList.toArray(new URL[0]));
            // ... and now you can pass the above classloader to Reflections
            controller =
                    (Class<? extends Annotation>)
                            loader.loadClass("org.springframework.stereotype.Controller");
            requestMapping =
                    (Class<? extends Annotation>)
                            loader.loadClass(
                                    "org.springframework.web.bind.annotation.RequestMapping");
            getMapping =
                    (Class<? extends Annotation>)
                            loader.loadClass("org.springframework.web.bind.annotation.GetMapping");
            postMapping =
                    (Class<? extends Annotation>)
                            loader.loadClass("org.springframework.web.bind.annotation.PostMapping");
            requestParam =
                    (Class<? extends Annotation>)
                            loader.loadClass(
                                    "org.springframework.web.bind.annotation.RequestParam");
            responseBody =
                    (Class<? extends Annotation>)
                            loader.loadClass(
                                    "org.springframework.web.bind.annotation.ResponseBody");
            requestHeader =
                    (Class<? extends Annotation>)
                            loader.loadClass(
                                    "org.springframework.web.bind.annotation.RequestHeader");
            modelAttribute =
                    (Class<? extends Annotation>)
                            loader.loadClass(
                                    "org.springframework.web.bind.annotation.ModelAttribute");
            requestBody =
                    (Class<? extends Annotation>)
                            loader.loadClass("org.springframework.web.bind.annotation.RequestBody");
            jsonIgnore =
                    (Class<? extends Annotation>)
                            loader.loadClass("com.fasterxml.jackson.annotation.JsonIgnore");
            jsonRawValue =
                    (Class<? extends Annotation>)
                            loader.loadClass("com.fasterxml.jackson.annotation.JsonRawValue");
            jsonFormat =
                    (Class<? extends Annotation>)
                            loader.loadClass("com.fasterxml.jackson.annotation.JsonFormat");
            notNull =
                    (Class<? extends Annotation>)
                            loader.loadClass("javax.validation.constraints.NotNull");
            pathVariable =
                    (Class<? extends Annotation>)
                            loader.loadClass(
                                    "org.springframework.web.bind.annotation.PathVariable");
            try {
                documentation =
                        (Class<? extends Annotation>)
                                loader.loadClass("ch.ubique.openapi.docannotations.Documentation");
            } catch (ClassNotFoundException ex) {
                documentation = Documentation.class;
            }
            for (String classString : controllers) {
                // check its metadata to see if it's what you want
                Class<?> myclass = loader.loadClass(classString);
                if (myclass.isAnnotationPresent(requestMapping)) {
                    requestClasses.add(myclass);
                }
            }

        } catch (DependencyResolutionRequiredException e) {
            new MojoExecutionException("Dependency resolution failed", e);
        } catch (ClassNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return requestClasses;
    }

    private String getType(java.lang.reflect.Field field) {
        if (javaToSwaggerLinkedHashMap.containsKey(field.getType().getSimpleName())) {
            return javaToSwaggerLinkedHashMap.get(field.getType().getSimpleName());
        } else {
            return field.getType().getSimpleName().toLowerCase();
        }
    }

    private List<String> registeredTypes = new ArrayList<>();

    private Map<String, Object> extractEnum(Class<?> objectClass) {
        try {
            Method m = objectClass.getDeclaredMethod("values");
            Object[] o = (Object[]) m.invoke(null);

            Map<String, Object> objDef = new LinkedHashMap<>();

            objDef.put("type", "string");

            objDef.put("enum", new ArrayList<String>());
            for (Object enumType : o) {
                ((ArrayList<String>) objDef.get("enum")).add(enumType.toString());
            }
            return objDef;

        } catch (SecurityException
                | InvocationTargetException
                | IllegalArgumentException
                | IllegalAccessException
                | NoSuchMethodException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return null;
    }

    private void addNamedModel(String name, Class<?> model) {
        Collection<Map<String, Object>> innerModDef = getModelDefinition(name, model);
        // add all additional found definitions
        for (Map<String, Object> defi : innerModDef) {
            Set<String> keys = defi.keySet();
            List<String> keysList = new ArrayList<>(keys);

            models.put(keysList.get(0), defi.values().toArray()[0]);
        }
    }

    private List<java.lang.reflect.Field> getAllFields(Class<?> theClass) {
        List<java.lang.reflect.Field> fields = new ArrayList<>();
        for (Class<?> c = theClass; c != null; c = c.getSuperclass()) {
            fields.addAll(Arrays.asList(c.getDeclaredFields()));
        }
        return fields;
    }

    private Collection<Map<String, Object>> getModelDefinition(String name, Class<?> objectClass) {
        ArrayList<Map<String, Object>> definitions = new ArrayList<>();
        Map<String, Object> topLevel = new LinkedHashMap<>();

        Map<String, Object> currentObject = new LinkedHashMap<>();
        // we are currently serializing this type so add it to registered types
        if (registeredTypes.contains(objectClass.getCanonicalName())) {
            return definitions;
        }
        // if we don't have a primitive and it  is not from our package, we probably have an
        // unserializable type
        boolean ownedType = false;
        boolean blackListed = false;
        if (blackListedPackages != null) {
            for (String basePackage : blackListedPackages) {
                if (objectClass.getCanonicalName().contains(basePackage)) {
                    ownedType = false;
                    blackListed = true;
                    break;
                }
            }
        }
        if (!blackListed) {
            for (String basePackage : basePackages) {
                if (objectClass.getCanonicalName().contains(basePackage)) {
                    ownedType = true;
                    break;
                }
            }
        }
        if (!ownedType && !isPrimitive(objectClass)) {
            return definitions;
        }
        // we havent returned yet, which means we have an actual type, register it!
        registeredTypes.add(objectClass.getCanonicalName());

        // ignore voids
        if (objectClass == Void.class) {
            return definitions;
        }

        if (isPrimitive(objectClass)) {
            // if we have a primitive just return
            return definitions;
        } else if (Enum.class.isAssignableFrom(objectClass)) {
            topLevel.put(objectClass.getCanonicalName(), extractEnum(objectClass));
            definitions.add(topLevel);
            return definitions;
        }

        List<String> required = new ArrayList<>();

        for (java.lang.reflect.Field field : getAllFields(objectClass)) {
            Class<?> type = field.getType();
            // Ignore @JsonIgnore fields
            if (field.isAnnotationPresent(jsonIgnore)) {
                getLog().warn("jsonIgnore");
                continue;
            }
            if (Modifier.isStatic(field.getModifiers())) {
                getLog().warn("static fields are ignored");
                continue;
            }
            if (!isPrimitive(type) && isBlackListed(type)) {
                getLog().warn("black listed type");
                continue;
            }
            Documentation docWrapper = null;
            if (field.isAnnotationPresent(documentation)) {
                docWrapper = new Documentation(field.getAnnotation(documentation));
                if (docWrapper.undocumented() && !field.isAnnotationPresent(notNull)) {
                    continue;
                } else if (docWrapper.undocumented() && field.isAnnotationPresent(notNull)) {
                    getLog().warn(
                                    "Got undocumented field, which is mandatory! The description"
                                            + " of this field will be empty.");
                }
                if (docWrapper.serializedClass() != NullType.class
                        && field.isAnnotationPresent(jsonRawValue)) {
                    type = docWrapper.serializedClass();
                }
            }
            // if a field is annotated as NotNull it is mandatory
            if (field.isAnnotationPresent(notNull)) {
                required.add(field.getName());
            }

            if (isPrimitive(type)) {
                // we have a primitive write directyl to map
                Map<String, Object> innerDef = new LinkedHashMap<>();
                currentObject.put(field.getName(), innerDef);
                JsonFormat format =
                        field.isAnnotationPresent(jsonFormat)
                                ? new JsonFormat(field.getAnnotation(jsonFormat))
                                : null;
                mapPrimitiveTypeAndFormat(innerDef, type.getSimpleName(), format, docWrapper);
                if (docWrapper != null) {
                    innerDef.put("description", docWrapper.description());
                    innerDef.put("example", docWrapper.example());
                }
            } else if (Collection.class.isAssignableFrom(type) || type.isArray()) {
                // we have a list

                Map<String, Object> arraydef = new LinkedHashMap<>();
                Map<String, String> ref = new LinkedHashMap<>();
                if (field.getGenericType() instanceof ParameterizedType) {
                    ParameterizedType innerType = (ParameterizedType) field.getGenericType();
                    java.lang.reflect.Type innerTypeClass = innerType.getActualTypeArguments()[0];
                    String innerTypeName = innerTypeClass.getTypeName();
                    Class<?> actualType = loadClass(innerTypeName);
                    if (!isPrimitive(actualType)) {
                        addNamedModel(field.getName(), actualType);
                        ref.put(
                                "$ref",
                                "#/components/schemas/" + (actualType.getCanonicalName() + ""));
                    } else {
                        ref.put("type", javaToSwaggerLinkedHashMap.get(actualType.getSimpleName()));
                    }
                    arraydef.put("type", "array");
                    arraydef.put("items", ref);
                    // arrays don't need an example as the example is taken from the examples of the
                    // elements
                    if (docWrapper != null) {
                        arraydef.put("description", docWrapper.description());
                    }
                    currentObject.put(field.getName(), arraydef);
                } else if (field.getGenericType() instanceof GenericArrayType) {
                    GenericArrayType innerType = (GenericArrayType) field.getGenericType();
                    String typeName = innerType.getTypeName();
                    Class<?> actualClass = loadClass(typeName);

                    if (!isPrimitive(actualClass)) {
                        Collection<Map<String, Object>> innerModDef =
                                getModelDefinition(field.getName(), actualClass);
                        // add all additional found definitions
                        for (Map<String, Object> defi : innerModDef) {
                            definitions.add(defi);
                        }
                    }
                    if (isPrimitive(actualClass)) {
                        ref.put(
                                "type",
                                javaToSwaggerLinkedHashMap.get(actualClass.getSimpleName()));
                    } else {
                        ref.put(
                                "$ref",
                                "#/components/schemas/" + (actualClass.getCanonicalName() + ""));
                    }
                    arraydef.put("type", "array");
                    if (docWrapper != null) {
                        arraydef.put("description", docWrapper.description());
                        arraydef.put("example", docWrapper.example());
                    }
                    arraydef.put("items", ref);
                    currentObject.put(field.getName(), arraydef);
                }

            } else if (Map.class.isAssignableFrom(type)) {
                Log logger = getLog();
                // ParameterizedType innerType = (ParameterizedType) field.getGenericType();
                // Class<?>[] innerTypeClass = (Class<?>[]) innerType.getActualTypeArguments();
                String keytypeName =
                        ((ParameterizedType) field.getGenericType())
                                .getActualTypeArguments()[0].getTypeName();
                String valuetypeName =
                        ((ParameterizedType) field.getGenericType())
                                .getActualTypeArguments()[1].getTypeName();
                Class<?> keyClass = loadClass(keytypeName);
                if (!isPrimitive(keyClass)) {
                    logger.error("Swagger definition does not allow complex keys");
                }
                Map<String, Object> objectMap = new LinkedHashMap<>();

                currentObject.put(field.getName(), objectMap);
                Class<?> actualClass = loadClass(valuetypeName);
                objectMap.put("type", "object");
                if (docWrapper != null) {
                    objectMap.put("description", docWrapper.description());
                    objectMap.put("example", docWrapper.example());
                }
                Map<String, Object> addProp = new LinkedHashMap<>();
                objectMap.put("additionalProperties", addProp);

                if (actualClass == null) {
                    // we have an array
                    Map<String, Object> lowerLevel = new LinkedHashMap<>();
                    // we have an array
                    addProp.put("type", "array");
                    addProp.put("items", lowerLevel);
                    while (actualClass == null) {
                        ParameterizedType listType =
                                (ParameterizedType)
                                        ((ParameterizedType) field.getGenericType())
                                                .getActualTypeArguments()[1];
                        actualClass = loadClass(listType.getActualTypeArguments()[0].getTypeName());
                        if (actualClass != null) {
                            lowerLevel.put(
                                    "$ref",
                                    "#/components/schemas/" + actualClass.getCanonicalName() + "");
                            Collection<Map<String, Object>> innerModDef =
                                    getModelDefinition(field.getName(), actualClass);
                            // add all additional found definitions
                            for (Map<String, Object> defi : innerModDef) {
                                definitions.add(defi);
                            }
                        } else {
                            lowerLevel.put("type", "array");
                            Map<String, Object> tmp = new LinkedHashMap<>();
                            lowerLevel.put("items", tmp);
                            lowerLevel = tmp;
                        }
                    }
                } else {
                    // just reference the object
                    if (isPrimitive(actualClass)) {
                        addProp.put(
                                "type",
                                javaToSwaggerLinkedHashMap.get(actualClass.getSimpleName()));
                    } else {
                        Collection<Map<String, Object>> innerModDef =
                                getModelDefinition(field.getName(), actualClass);
                        // add all additional found definitions
                        for (Map<String, Object> defi : innerModDef) {
                            definitions.add(defi);
                        }
                        addProp.put(
                                "$ref", "#/components/schemas/" + actualClass.getCanonicalName());
                    }
                }
            } else {
                if (isPrimitive(type)) {
                    Map<String, Object> objDefinition = new LinkedHashMap<>();
                    currentObject.put(field.getName(), objDefinition);
                    objDefinition.put("type", javaToSwaggerLinkedHashMap.get(type.getSimpleName()));
                    if (docWrapper != null) {
                        objDefinition.put("description", docWrapper.description());
                        objDefinition.put("example", docWrapper.example());
                    }
                    continue;
                }
                Map<String, Object> objDefinition = new LinkedHashMap<>();
                currentObject.put(field.getName(), objDefinition);
                objDefinition.put("$ref", "#/components/schemas/" + type.getCanonicalName() + "");
                if (docWrapper != null) {
                    objDefinition.put("description", docWrapper.description());
                    objDefinition.put("example", docWrapper.example());
                }
                // only add types which are not primitives AND from us (prevents weird private
                // fields from
                // trying to be serialized)

                if (!registeredTypes.contains(type.getSimpleName()) && ownedType) {
                    Collection<Map<String, Object>> innerModDef =
                            getModelDefinition(field.getName(), type);
                    // add all additional found definitions
                    for (Map<String, Object> defi : innerModDef) {
                        definitions.add(defi);
                    }
                }
            }
        }
        Map<String, Object> objDef = new LinkedHashMap<>();
        topLevel.put(objectClass.getCanonicalName(), objDef);
        objDef.put("type", "object");
        if (required.size() > 0) {
            objDef.put("required", required);
        }
        objDef.put("properties", currentObject);
        definitions.add(0, topLevel);
        getLog().info("we have " + definitions.size() + " definitions added");
        return definitions;
    }

    private boolean isPrimitive(Class<?> field) {
        return javaToSwaggerLinkedHashMap.containsKey(field.getSimpleName());
    }

    private boolean isBlackListed(Class<?> field) {
        if (blackListedPackages != null) {
            for (String blackListed : blackListedPackages) {
                if (field.getCanonicalName().contains(blackListed)) {
                    return true;
                }
            }
        }
        if (ignoredTypes != null) {
            for (String ignoredType : ignoredTypes) {
                if (field.getCanonicalName().equals(ignoredType)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void mapPrimitiveTypeAndFormat(Map<String, Object> mapToAdd, String type) {
        String mappedType = javaToSwaggerLinkedHashMap.get(type);
        mapToAdd.put("type", mappedType);
        if (type.equals("double")
                || type.equals("float")
                || type.equals("Double")
                || type.equals("Float")) {
            mapToAdd.put("format", type.toLowerCase());
        } else if (type.equals("long") || type.equals("Long")) {
            mapToAdd.put("format", type.toLowerCase());
        } else if (type.equals("URL")) {
            mapToAdd.put("pattern", "(https?|ftps?|file):\\/\\/((.+\\.)+)[A-z]{2,}((\\/.+)*)");
        }
    }

    private void mapPrimitiveTypeAndFormat(
            Map<String, Object> mapToAdd,
            String type,
            JsonFormat format,
            Documentation documentation) {
        String mappedType = javaToSwaggerLinkedHashMap.get(type);
        if (format != null) {
            switch (format.shape()) {
                case STRING:
                    mappedType = "string";
                    if (documentation == null
                            || (documentation != null
                                    && (documentation.description() == null
                                            || documentation.description().isBlank()))) {
                        mapToAdd.put("pattern", format.pattern());
                    }
                    break;
                case NUMBER_INT:
                    mappedType = "integer";
                    break;
                case NUMBER:
                case NUMBER_FLOAT:
                    mappedType = "number";
                    break;
                default:
                    break;
            }
        }
        mapToAdd.put("type", mappedType);
        if (type.equals("double")
                || type.equals("float")
                || type.equals("Double")
                || type.equals("Float")) {
            mapToAdd.put("format", type.toLowerCase());
        } else if (type.equals("long") || type.equals("Long")) {
            mapToAdd.put("format", type.toLowerCase());
        } else if (type.equals("URL")) {
            mapToAdd.put("pattern", "(https?|ftps?|file):\\/\\/((.+\\.)+)[A-z]{2,}((\\/.+)*)");
        }
    }

    static LinkedHashMap<String, String> javaToSwaggerLinkedHashMap;

    static {
        javaToSwaggerLinkedHashMap = new LinkedHashMap<>();
        javaToSwaggerLinkedHashMap.put("DateTime", "integer");
        javaToSwaggerLinkedHashMap.put("Date", "integer");
        javaToSwaggerLinkedHashMap.put("Double", "number");
        javaToSwaggerLinkedHashMap.put("double", "number");
        javaToSwaggerLinkedHashMap.put("Float", "number");
        javaToSwaggerLinkedHashMap.put("float", "number");
        javaToSwaggerLinkedHashMap.put("String", "string");
        javaToSwaggerLinkedHashMap.put("int", "integer");
        javaToSwaggerLinkedHashMap.put("Integer", "integer");
        javaToSwaggerLinkedHashMap.put("boolean", "boolean");
        javaToSwaggerLinkedHashMap.put("Boolean", "boolean");
        javaToSwaggerLinkedHashMap.put("Long", "integer");
        javaToSwaggerLinkedHashMap.put("long", "integer");
        javaToSwaggerLinkedHashMap.put("Object", "object");
        javaToSwaggerLinkedHashMap.put("Void", "");
        javaToSwaggerLinkedHashMap.put("void", "");
        javaToSwaggerLinkedHashMap.put("URL", "string");
    }

    // helper function to get functions in order of bytecode
    static class MethodOffset implements Comparable<MethodOffset> {
        MethodOffset(Method _method, int _offset) {
            method = _method;
            offset = _offset;
        }

        @Override
        public int compareTo(MethodOffset target) {
            return offset - target.offset;
        }

        Method method;
        int offset;
    }

    static class ByLength implements Comparator<Method> {

        @Override
        public int compare(Method a, Method b) {
            return b.getName().length() - a.getName().length();
        }
    }

    /** Grok the bytecode to get the declared order */
    public static Method[] getDeclaredMethodsInOrder(Class clazz) {
        Method[] methods = null;
        try {
            String resource = clazz.getName().replace('.', '/') + ".class";

            methods = clazz.getDeclaredMethods();

            InputStream is = clazz.getClassLoader().getResourceAsStream(resource);

            if (is == null) {
                return methods;
            }

            java.util.Arrays.sort(methods, new ByLength());
            List<byte[]> blocks = new ArrayList<>();
            int length = 0;
            for (; ; ) {
                byte[] block = new byte[16 * 1024];
                int n = is.read(block);
                if (n > 0) {
                    if (n < block.length) {
                        block = java.util.Arrays.copyOf(block, n);
                    }
                    length += block.length;
                    blocks.add(block);
                } else {
                    break;
                }
            }

            byte[] data = new byte[length];
            int offset = 0;
            for (byte[] block : blocks) {
                System.arraycopy(block, 0, data, offset, block.length);
                offset += block.length;
            }

            String sdata = new String(data, java.nio.charset.Charset.forName("UTF-8"));
            int lnt = sdata.indexOf("LineNumberTable");
            if (lnt != -1) sdata = sdata.substring(lnt + "LineNumberTable".length() + 3);
            int cde = sdata.lastIndexOf("SourceFile");
            if (cde != -1) sdata = sdata.substring(0, cde);

            MethodOffset mo[] = new MethodOffset[methods.length];

            for (int i = 0; i < methods.length; ++i) {
                int pos = -1;
                for (; ; ) {
                    pos = sdata.indexOf(methods[i].getName(), pos);
                    if (pos == -1) break;
                    boolean subset = false;
                    for (int j = 0; j < i; ++j) {
                        if (mo[j].offset >= 0
                                && mo[j].offset <= pos
                                && pos < mo[j].offset + mo[j].method.getName().length()) {
                            subset = true;
                            break;
                        }
                    }
                    if (subset) {
                        pos += methods[i].getName().length();
                    } else {
                        break;
                    }
                }
                mo[i] = new MethodOffset(methods[i], pos);
            }
            java.util.Arrays.sort(mo);
            for (int i = 0; i < mo.length; ++i) {
                methods[i] = mo[i].method;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return methods;
    }
}
