// Copyright (c) 2019, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.json;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static java.util.Map.of;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.junit.MatcherAssert.assertThat;

class YamlDocGeneratorTest {
  private static final String K8S_VERSION = "1.13.5";
  private final SchemaGenerator schemaGenerator = new SchemaGenerator();
  @SuppressWarnings("unused")
  @Description("An annotated field")
  private Double annotatedDouble;
  @SuppressWarnings("unused")
  private OffsetDateTime dateTime;
  @SuppressWarnings("unused")
  private Map<String, String> notes;
  @SuppressWarnings("unused")
  private List<String> myList;
  @SuppressWarnings("unused")
  @Description("An example")
  private SimpleObject simpleUsage;
  @SuppressWarnings("unused")
  @Description("An array")
  private SimpleObject[] simpleArray;

  @Test
  void generateMarkdownForProperty() throws NoSuchFieldException {
    String markdown = generateForProperty(getClass().getDeclaredField("annotatedDouble"));
    assertThat(
        markdown, containsString(tableEntry("`annotatedDouble`", "number", "An annotated field")));
  }

  private String tableEntry(String... columns) {
    return "| " + String.join(" | ", columns) + " |";
  }

  private String generateForProperty(Field field) {
    YamlDocGenerator generator = new YamlDocGenerator(new HashMap<>());

    return generator.generateForProperty(field.getName(), generateSchemaForField(field));
  }

  private Map<String, Object> generateSchemaForField(Field field) {
    Map<String, Object> result = new HashMap<>();
    schemaGenerator.generateFieldIn(result, field);
    return result;
  }

  @Test
  void whenSchemaHasUknownTypeAndNoReference_useAsSpecified() {
    Map<String, Object> schema = of("anInt", of("type", "integer"));

    String markdown = new YamlDocGenerator(schema).generateForProperty("anInt", schema);

    assertThat(markdown, containsString(tableEntry("`anInt`", "integer", "")));
  }

  @Test
  void whenPropertyTypeIsDateTime_doNotGenerateReference() throws NoSuchFieldException {
    String markdown = generateForProperty(getClass().getDeclaredField("dateTime"));
    assertThat(markdown, containsString(tableEntry("`dateTime`", "DateTime", "")));
  }

  @Test
  void whenPropertyTypeIsMap_doNotGenerateReference() throws NoSuchFieldException {
    String markdown = generateForProperty(getClass().getDeclaredField("notes"));
    assertThat(markdown, containsString(tableEntry("`notes`", "Map", "")));
  }

  @Test
  void whenPropertyTypeIsArrayOfStrings_generateType() throws NoSuchFieldException {
    String markdown = generateForProperty(getClass().getDeclaredField("myList"));
    assertThat(markdown, containsString(tableEntry("`myList`", "Array of string", "")));
  }

  @Test
  void whenPropertyTypeIsReferenceWithDescription_includeBoth() throws NoSuchFieldException {
    String markdown = generateForProperty(getClass().getDeclaredField("simpleUsage"));
    assertThat(
        markdown,
        containsString(
            tableEntry("`simpleUsage`", linkTo("Simple Object", "#simple-object"), "An example")));
  }

  private String linkTo(String section, String anchor) {
    return "[" + section + "](" + anchor + ")";
  }

  @Test
  void whenPropertyTypeIsReferenceArrayWithDescription_includeBoth()
      throws NoSuchFieldException {
    String markdown = generateForProperty(getClass().getDeclaredField("simpleArray"));
    assertThat(
        markdown,
        containsString(
            tableEntry(
                "`simpleArray`",
                "Array of " + linkTo("Simple Object", "#simple-object"),
                "An array")));
  }

  @Test
  void generateMarkdownForSimpleObject() {
    YamlDocGenerator generator = new YamlDocGenerator(new HashMap<>());
    String markdown = generator.generateForClass(generateSchema(SimpleObject.class));
    assertThat(
        markdown,
        containsString(
            String.join(
                "\n",
                tableHeader(),
                tableEntry("`aaBoolean`", "Boolean", "A flag"),
                tableEntry("`aaString`", "string", "A string"),
                tableEntry("`depth`", "number", ""))));
  }

  private Map<String, Object> generateSchema(Class<?> aaClass) {
    return schemaGenerator.generate(aaClass);
  }

  @Test
  void generateMarkdownForSimpleObjectWithHeader() {
    Map<String, Object> schema = generateSchema(SimpleObject.class);
    YamlDocGenerator generator = new YamlDocGenerator(schema);
    String markdown = generator.generate("simpleObject");
    assertThat(
        markdown,
        containsString(
            String.join(
                "\n",
                "### Simple Object",
                "",
                tableHeader(),
                tableEntry("`aaBoolean`", "Boolean", "A flag"),
                tableEntry("`aaString`", "string", "A string"),
                tableEntry("`depth`", "number", ""))));
  }

  @Test
  void generateMarkdownForObjectWithReferences() {
    Map<String, Object> schema = generateSchema(ReferencingObject.class);
    YamlDocGenerator generator = new YamlDocGenerator(schema);
    String markdown = generator.generate("start");
    assertThat(
        markdown,
        containsString(
            String.join(
                "\n",
                "### Start",
                "",
                tableHeader(),
                tableEntry("`deprecatedField`", "integer", ""),
                tableEntry("`derived`", linkTo("Derived Object", "#derived-object"), ""),
                tableEntry("`simple`", linkTo("Simple Object", "#simple-object"), ""))));
  }

  @Test
  void whenKubernetesSchemaNotUsed_kubernetesMarkdownIsNull() {
    Map<String, Object> schema = generateSchema(ReferencingObject.class);
    YamlDocGenerator generator = new YamlDocGenerator(schema);
    generator.generate("start");
    assertThat(generator.getKubernetesSchemaMarkdown(), nullValue());
  }

  @Test
  void generateMarkdownWithReferencedSections() {
    Map<String, Object> schema = generateSchema(ReferencingObject.class);
    YamlDocGenerator generator = new YamlDocGenerator(schema);
    String markdown = generator.generate("start");
    assertThat(
        markdown,
        containsString(
            String.join(
                "\n",
                "### Start",
                "",
                tableHeader(),
                tableEntry("`deprecatedField`", "integer", ""),
                tableEntry("`derived`", linkTo("Derived Object", "#derived-object"), ""),
                tableEntry("`simple`", linkTo("Simple Object", "#simple-object"), ""),
                "",
                "### Derived Object",
                "",
                "A simple object used for testing",
                "",
                tableHeader(),
                tableEntry("`aaBoolean`", "Boolean", "A flag"),
                tableEntry("`aaString`", "string", "A string"))));
  }

  private String tableHeader() {
    return tableHeader("Name", "Type", "Description");
  }

  private String tableHeader(String... headers) {
    return tableEntry(headers) + "\n" + tableDivider(headers.length);
  }

  private String tableDivider(int numColumns) {
    return "|" + " --- |".repeat(Math.max(0, numColumns));
  }
}
