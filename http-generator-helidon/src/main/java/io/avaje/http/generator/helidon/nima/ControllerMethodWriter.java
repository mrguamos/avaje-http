package io.avaje.http.generator.helidon.nima;

import static io.avaje.http.generator.core.ProcessingContext.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.avaje.http.generator.core.Append;
import io.avaje.http.generator.core.CoreWebMethod;
import io.avaje.http.generator.core.MethodParam;
import io.avaje.http.generator.core.MethodReader;
import io.avaje.http.generator.core.ParamType;
import io.avaje.http.generator.core.PathSegments;
import io.avaje.http.generator.core.UType;
import io.avaje.http.generator.core.WebMethod;
import io.avaje.http.generator.core.openapi.MediaType;

import javax.lang.model.type.TypeMirror;

/**
 * Write code to register Web route for a given controller method.
 */
final class ControllerMethodWriter {

  private static final Map<Integer,String> statusMap = new HashMap<>();
  static {
    statusMap.put(200, "OK_200");
    statusMap.put(201, "CREATED_201");
    statusMap.put(202, "ACCEPTED_202");
    statusMap.put(204, "NO_CONTENT_204");
    statusMap.put(205, "RESET_CONTENT_205");
    statusMap.put(206, "PARTIAL_CONTENT_206");

    statusMap.put(400, "BAD_REQUEST_400");
    statusMap.put(401, "UNAUTHORIZED_401");
    statusMap.put(402, "PAYMENT_REQUIRED_402");
    statusMap.put(403, "FORBIDDEN_403");
    statusMap.put(404, "NOT_FOUND_404");
    statusMap.put(405, "METHOD_NOT_ALLOWED_405");
    statusMap.put(406, "NOT_ACCEPTABLE_406");
    statusMap.put(407, "PROXY_AUTHENTICATION_REQUIRED_407");
    statusMap.put(408, "REQUEST_TIMEOUT_408");
    statusMap.put(409, "CONFLICT_409");
    statusMap.put(410, "GONE_410");
    statusMap.put(411, "LENGTH_REQUIRED_411");
    statusMap.put(412, "PRECONDITION_FAILED_412");
    statusMap.put(413, "REQUEST_ENTITY_TOO_LARGE_413");
    statusMap.put(414, "REQUEST_URI_TOO_LONG_414");
    statusMap.put(415, "UNSUPPORTED_MEDIA_TYPE_415");
    statusMap.put(416, "REQUESTED_RANGE_NOT_SATISFIABLE_416");
    statusMap.put(417, "EXPECTATION_FAILED_417");
    statusMap.put(418, "I_AM_A_TEAPOT_418");

    statusMap.put(500, "INTERNAL_SERVER_ERROR_500");
    statusMap.put(501, "NOT_IMPLEMENTED_501");
    statusMap.put(502, "BAD_GATEWAY_502");
    statusMap.put(503, "SERVICE_UNAVAILABLE_503");
    statusMap.put(504, "GATEWAY_TIMEOUT_504");
    statusMap.put(505, "HTTP_VERSION_NOT_SUPPORTED_505");
  }

  private final MethodReader method;
  private final Append writer;
  private final WebMethod webMethod;
  private final boolean useJsonB;
  private final boolean instrumentContext;
  private final boolean isFilter;

  ControllerMethodWriter(MethodReader method, Append writer, boolean useJsonB) {
    this.method = method;
    this.writer = writer;
    this.webMethod = method.webMethod();
    this.useJsonB = useJsonB;
    this.instrumentContext = method.instrumentContext();
    this.isFilter = webMethod == CoreWebMethod.FILTER;
    if (isFilter) {
      validateMethod();
    }
  }

  private void validateMethod() {
    if (method.params().stream().map(MethodParam::shortType).noneMatch("FilterChain"::equals)) {
      logError(method.element(), "Filters must contain a FilterChain Parameter");
    }
  }

  void writeRule() {
    if (method.isErrorMethod()) {
      writer.append("    routing.error(%s.class, this::_%s);", method.exceptionShortName(), method.simpleName()).eol();
    } else if (isFilter) {
      writer.append("    routing.addFilter(this::_%s);", method.simpleName()).eol();
    } else {
      writer.append("    routing.%s(\"%s\", this::_%s);", webMethod.name().toLowerCase(), method.fullPath(), method.simpleName()).eol();
    }
  }

  void writeHandler(boolean requestScoped) {
    if (method.isErrorMethod()) {
      writer.append("  private void _%s(ServerRequest req, ServerResponse res, %s ex) {", method.simpleName(), method.exceptionShortName()).eol();
    } else if (isFilter) {
      writer.append("  private void _%s(FilterChain chain, RoutingRequest req, RoutingResponse res) {", method.simpleName()).eol();
    } else {
      writer.append("  private void _%s(ServerRequest req, ServerResponse res) throws Exception {", method.simpleName()).eol();
    }

    if (!isFilter) {
      int statusCode = method.statusCode();
      if (statusCode > 0) {
        writer.append("    res.status(%s);", lookupStatusCode(statusCode)).eol();
      }
    }

    final var bodyType = method.bodyType();
    if (bodyType != null && !method.isErrorMethod() && !isFilter) {
      if ("InputStream".equals(bodyType)) {
        writer.append("    var %s = req.content().inputStream();", method.bodyName()).eol();
      } else if ("String".equals(bodyType)) {
        writer.append("    var %s = req.content().as(String.class);", method.bodyName()).eol();
      } else if (useJsonB) {
        final String fieldName = fieldNameOfBody();
        writer.append("    var %s = %sJsonType.fromJson(req.content().inputStream());", method.bodyName(), fieldName).eol();
      } else {
        defaultHelidonBodyContent();
      }
    } else if (usesFormParams()) {
      writer.append("    var formParams = req.content().as(Parameters.class);").eol();
    }

    final var segments = method.pathSegments();
    if (segments.fullPath().contains("{")) {
      writer.append("    var pathParams = req.path().pathParameters();").eol();
    }

    for (final PathSegments.Segment matrixSegment : segments.matrixSegments()) {
      matrixSegment.writeCreateSegment(writer, platform());
    }

    final var params = method.params();
    for (final MethodParam param : params) {
      if (!isExceptionOrFilterChain(param)) {
        param.writeCtxGet(writer, segments);
      }
    }

    if (method.includeValidate()) {
      for (final MethodParam param : params) {
        param.writeValidate(writer);
      }
    }

    writer.append("    ");
    if (!method.isVoid()) {
      writer.append("var result = ");
    } else if (missingServerResponse(params)) {
      logError(method.element(), "Void controller methods must have a ServerResponse parameter");
    }

    if (instrumentContext) {
      method.writeContext(writer, "req", "res");
    }

    if (requestScoped) {
      writer.append("factory.create(req, res).");
    } else {
      writer.append("controller.");
    }
    writer.append(method.simpleName()).append("(");
    for (var i = 0; i < params.size(); i++) {
      if (i > 0) {
        writer.append(", ");
      }
      final var param = params.get(i);
      if (isAssignable2Interface(param.utype().mainType(), "java.lang.Exception")) {
        writer.append("ex");
      } else if ("FilterChain".equals(param.shortType())) {
        writer.append("chain");
      } else {
        param.buildParamName(writer);
      }
    }
    if (instrumentContext) {
      writer.append(")");
    }
    writer.append(");").eol();

    if (!method.isVoid() && !isFilter) {
      writeContextReturn();
      if (isInputStream(method.returnType())) {
        final var uType = UType.parse(method.returnType());
        writer.append("    result.transferTo(res.outputStream());", uType.shortName()).eol();
      } else if (producesJson()) {
        if (returnTypeString()) {
          writer.append("    res.send(result); // send raw JSON").eol();
        } else {
          final var uType = UType.parse(method.returnType());
          writer.append("    %sJsonType.toJson(result, JsonOutput.of(res));", uType.shortName()).eol();
        }
      } else {
        writer.append("    res.send(result);").eol();
      }
    }
    writer.append("  }").eol().eol();
  }

  private static boolean isExceptionOrFilterChain(MethodParam param) {
    return isAssignable2Interface(param.utype().mainType(), "java.lang.Exception")
      || "FilterChain".equals(param.shortType());
  }

  private boolean isInputStream(TypeMirror type) {
    return isAssignable2Interface(type.toString(), "java.io.InputStream");
  }

  private void defaultHelidonBodyContent() {
    method.params().stream()
        .filter(MethodParam::isBody)
        .forEach(
            param -> {
              final var type = param.utype();
              writer.append("    var %s = req.content()", method.bodyName());
              writer.append(".as(");
              if (type.param0() != null) {
                writer.append("new io.helidon.common.GenericType<%s>() {}", type.full());
              } else {
                writer.append("%s.class", type.full());
              }
              writer.append(");").eol();
            });
  }

  private String fieldNameOfBody() {
    return method.params().stream()
        .filter(MethodParam::isBody)
        .findFirst()
        .orElseThrow()
        .utype()
        .shortName();
  }

  private boolean producesJson() {
    return useJsonB
        && !disabledDirectWrites()
        && !"byte[]".equals(method.returnType().toString())
        && (method.produces() == null || method.produces().toLowerCase().contains("json"));
  }

  private boolean returnTypeString() {
    return "java.lang.String".equals(method.returnType().toString());
  }

  private boolean missingServerResponse(List<MethodParam> params) {
    return method.isVoid()
        && !isFilter
        && params.stream().noneMatch(p -> "ServerResponse".equals(p.shortType()));
  }

  private boolean usesFormParams() {
    return method.params().stream().anyMatch(p -> p.isForm() || ParamType.FORMPARAM.equals(p.paramType()));
  }

  private void writeContextReturn() {
    final var producesOp = Optional.ofNullable(method.produces());
    if (producesOp.isEmpty() && !useJsonB) {
      return;
    }

    final var produces = producesOp.map(MediaType::parse).orElse(MediaType.APPLICATION_JSON);
    final var contentTypeString = "    res.headers().contentType(MediaTypes.";
    switch (produces) {
      case APPLICATION_JSON -> writer.append(contentTypeString).append("APPLICATION_JSON);").eol();
      case TEXT_HTML -> writer.append(contentTypeString).append("TEXT_HTML);").eol();
      case TEXT_PLAIN -> writer.append(contentTypeString).append("TEXT_PLAIN);").eol();
      case UNKNOWN -> writer.append(contentTypeString + "create(\"%s\"));", producesOp.orElse("UNKNOWN")).eol();
    }
  }

  private String lookupStatusCode(int statusCode) {
    return statusMap.getOrDefault(statusCode, String.valueOf(statusCode));
  }

  public void buildApiDocumentation() {
    method.buildApiDoc();
  }
}
