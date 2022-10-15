package io.avaje.http.generator.helidon.nima;

import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;

import javax.lang.model.type.DeclaredType;

import io.avaje.http.generator.core.BaseControllerWriter;
import io.avaje.http.generator.core.Constants;
import io.avaje.http.generator.core.ControllerReader;
import io.avaje.http.generator.core.MethodReader;
import io.avaje.http.generator.core.ProcessingContext;

/** Write Helidon specific web route adapter (a Helidon Service). */
class ControllerWriter extends BaseControllerWriter {

  private static final String AT_GENERATED = "@Generated(\"avaje-helidon-nima-generator\")";
  private final boolean useJsonB;
  private List<MethodReader> jsonBMethodList;

  ControllerWriter(ControllerReader reader, ProcessingContext ctx, boolean jsonB)
      throws IOException {
    super(reader, ctx);
    useJsonB = jsonB;
    if (useJsonB) {
      reader.addImportType("io.avaje.jsonb.Jsonb");
      reader.addImportType("io.avaje.jsonb.JsonType");
      jsonBMethodList =
          reader.getMethods().stream()
              .filter(MethodReader::isWebMethod)
              .filter(Predicate.not(MethodReader::isVoid))
              .filter(m -> !"byte[]".equals(m.getReturnType().toString()))
              .filter(
                  m -> m.getProduces() == null || m.getProduces().toLowerCase().contains("json"))
              .toList();
    }
    // reader.addImportType("io.helidon.common.http.FormParams");
    reader.addImportType("io.helidon.nima.webserver.http.HttpRules");
    reader.addImportType("io.helidon.nima.webserver.http.HttpRouting");
    reader.addImportType("io.helidon.nima.webserver.http.ServerRequest");
    reader.addImportType("io.helidon.nima.webserver.http.ServerResponse");
    // reader.addImportType("io.helidon.nima.webserver.Routing");
    // reader.addImportType("java.util.function.Supplier");
    reader.addImportType("io.helidon.nima.webserver.http.HttpService");
  }

  void write() {
    writePackage();
    writeImports();
    writeClassStart();
    writeAddRoutes();
    writeClassEnd();
  }

  private List<ControllerMethodWriter> getWriterMethods() {
    return reader.getMethods().stream()
        .filter(MethodReader::isWebMethod)
        .map(it -> new ControllerMethodWriter(it, writer, ctx, useJsonB))
        .toList();
  }

  private void writeAddRoutes() {
    final var methods = getWriterMethods();
    writeRoutes(methods);
    for (final ControllerMethodWriter methodWriter : methods) {
      methodWriter.writeHandler(isRequestScoped());
    }
  }

  private void writeRoutes(List<ControllerMethodWriter> methods) {
    writer.append("  @Override").eol();
    writer.append("  public void routing(HttpRules rules) {").eol();
    // writer.append("    var rules = HttpRouting.builder();").eol();
    for (final ControllerMethodWriter methodWriter : methods) {
      methodWriter.writeRule();
      if (!reader.isDocHidden()) {
        methodWriter.buildApiDocumentation();
      }
    }
    // writer.append("    return rules.build();").eol().eol();
    writer.append("  }").eol().eol();
  }

  private void writeClassStart() {
    writer.append(AT_GENERATED).eol();
    writer.append("@Component").eol();
    writer
        .append("public class ")
        .append(shortName)
        .append("$Route implements HttpService {")
        .eol()
        .eol();

    var controllerName = "controller";
    var controllerType = shortName;
    if (isRequestScoped()) {
      controllerName = "factory";
      controllerType += Constants.FACTORY_SUFFIX;
    }
    writer.append("  private final %s %s;", controllerType, controllerName).eol();

    if (reader.isIncludeValidator()) {
      writer.append("  private final Validator validator;").eol();
    }

    if (useJsonB) {
      writeJsonBTypeFields();
    }

    writer.eol();

    writer.append("  public %s$Route(%s %s", shortName, controllerType, controllerName);
    if (reader.isIncludeValidator()) {
      writer.append(", Validator validator");
    }

    if (useJsonB) {
      writer.append(", Jsonb jsonB");
    }

    writer.append(") {").eol();
    writer.append("    this.%s = %s;", controllerName, controllerName).eol();
    if (reader.isIncludeValidator()) {
      writer.append("    this.validator = validator;").eol();
    }

    if (useJsonB) {
      writeJsonBTypeAssignments();
    }

    writer.append("  }").eol().eol();
  }

  public void writeJsonBTypeFields() {
    for (final MethodReader methodReader : jsonBMethodList) {
      if (methodReader.getReturnType() instanceof final DeclaredType fullType) {
        final var typeArgs = fullType.getTypeArguments();
        final var typeArgSize = typeArgs.size();

        writer.append("  private final JsonType<");
        switch (typeArgSize) {
          case 1 -> {
            if (fullType.toString().contains("java.util.Set"))
              writer.append("java.util.Set<%s>>", typeArgs.get(0));
            else writer.append("java.util.List<%s>>", typeArgs.get(0));
          }
          case 2 -> writer.append("java.util.Map<String, %s>>", typeArgs.get(1));
          default -> writer.append("%s>", fullType);
        }
        writer.append(" %sMethodReturnJsonType;", methodReader.simpleName()).eol();
      } else {
        throw new UnsupportedOperationException(
            "Only Objects and Strings are supported with Jsonb Return Types");
      }
    }
  }

  public void writeJsonBTypeAssignments() {

    for (final MethodReader methodReader : jsonBMethodList) {
      if (methodReader.getReturnType() instanceof final DeclaredType fullType) {
        final var typeArgs = fullType.getTypeArguments();
        final var typeArgSize = typeArgs.size();
        final var jsonType =
            switch (typeArgSize) {
              case 1 -> typeArgs.get(0);
              case 2 -> typeArgs.get(1);
              default -> fullType;
            };

        writer.append(
            "    this.%sMethodReturnJsonType = jsonB.type(%s.class)",
            methodReader.simpleName(), jsonType);
        final var returnType = fullType.toString();
        if (typeArgSize != 0) {
          writer.append(".");
          if (returnType.contains("java.util.List")) writer.append("list");
          else if (returnType.contains("java.util.Map")) writer.append("map");
          else if (returnType.contains("java.util.Set")) writer.append("set");
          else {
            throw new UnsupportedOperationException(
                "Only java.util Map, Set and List are supported JsonB Controller Collection Return Types");
          }
          writer.append("()");
        }
        writer.append(";").eol();

      } else {
        throw new UnsupportedOperationException(
            "Only Objects and Strings are supported with Jsonb Controller Return Types");
      }
    }
  }
}
