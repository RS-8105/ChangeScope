package com.changescope.service;

import com.changescope.dto.ParsedConstructor;
import com.changescope.dto.ParsedField;
import com.changescope.dto.ParsedJavaFile;
import com.changescope.dto.ParsedMethod;
import com.changescope.dto.ParsedParameter;
import com.changescope.dto.ParsedType;
import com.changescope.dto.TypeKind;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class JavaSourceParserService {

    private final JavaParser javaParser;

    public JavaSourceParserService() {
        ParserConfiguration configuration = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        this.javaParser = new JavaParser(configuration);
    }

    public ParsedJavaFile parse(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) {
            return ParsedJavaFile.error("Java source code must not be null or empty");
        }

        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(sourceCode);

            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                String errorDetails = parseResult.getProblems().stream()
                    .map(Problem::getMessage)
                    .collect(Collectors.joining("; "));
                return ParsedJavaFile.error(
                    errorDetails.isBlank() ? "Failed to parse Java source syntax" : "Syntax error: " + errorDetails
                );
            }

            CompilationUnit cu = parseResult.getResult().get();

            String packageName = cu.getPackageDeclaration()
                .map(NodeWithName::getNameAsString)
                .orElse(null);

            List<String> imports = cu.getImports().stream()
                .map(imp -> imp.getNameAsString() + (imp.isAsterisk() ? ".*" : ""))
                .toList();

            List<ParsedType> types = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .map(this::extractType)
                .toList();

            return ParsedJavaFile.success(packageName, imports, types);
        } catch (Exception ex) {
            return ParsedJavaFile.error("Unexpected error during parsing: " + ex.getMessage());
        }
    }

    private ParsedType extractType(ClassOrInterfaceDeclaration declaration) {
        String name = declaration.getNameAsString();
        TypeKind kind = declaration.isInterface() ? TypeKind.INTERFACE : TypeKind.CLASS;

        List<String> extendedTypes = declaration.getExtendedTypes().stream()
            .flatMap(t -> extractTypeNames(t).stream())
            .toList();

        List<String> implementedTypes = declaration.getImplementedTypes().stream()
            .flatMap(t -> extractTypeNames(t).stream())
            .toList();

        List<ParsedField> fields = declaration.getFields().stream()
            .flatMap(f -> extractFields(f).stream())
            .toList();

        List<ParsedConstructor> constructors = declaration.getConstructors().stream()
            .map(this::extractConstructor)
            .toList();

        List<ParsedMethod> methods = declaration.getMethods().stream()
            .map(this::extractMethod)
            .toList();

        return new ParsedType(name, kind, extendedTypes, implementedTypes, fields, methods, constructors);
    }

    private List<ParsedField> extractFields(FieldDeclaration fieldDeclaration) {
        Type type = fieldDeclaration.getElementType();
        String typeString = type.asString();
        List<String> referenced = extractTypeNames(type);

        return fieldDeclaration.getVariables().stream()
            .map(v -> new ParsedField(typeString, v.getNameAsString(), referenced))
            .toList();
    }

    private ParsedConstructor extractConstructor(ConstructorDeclaration constructor) {
        String name = constructor.getNameAsString();
        List<ParsedParameter> parameters = constructor.getParameters().stream()
            .map(p -> new ParsedParameter(p.getTypeAsString(), p.getNameAsString()))
            .toList();

        List<String> parameterTypes = parameters.stream()
            .map(ParsedParameter::type)
            .toList();

        List<String> referencedTypes = constructor.getParameters().stream()
            .flatMap(p -> extractTypeNames(p.getType()).stream())
            .toList();

        return new ParsedConstructor(name, parameterTypes, parameters, referencedTypes);
    }

    private ParsedMethod extractMethod(MethodDeclaration method) {
        String name = method.getNameAsString();
        String returnType = method.getTypeAsString();

        List<ParsedParameter> parameters = method.getParameters().stream()
            .map(p -> new ParsedParameter(p.getTypeAsString(), p.getNameAsString()))
            .toList();

        List<String> parameterTypes = parameters.stream()
            .map(ParsedParameter::type)
            .toList();

        List<String> returnTypeReferencedTypes = extractTypeNames(method.getType());
        List<String> parameterReferencedTypes = method.getParameters().stream()
            .flatMap(p -> extractTypeNames(p.getType()).stream())
            .toList();

        return new ParsedMethod(
            name,
            returnType,
            parameterTypes,
            parameters,
            returnTypeReferencedTypes,
            parameterReferencedTypes
        );
    }

    public List<String> extractTypeNames(Type type) {
        List<String> names = new ArrayList<>();
        collectTypeNames(type, names);
        return names;
    }

    private void collectTypeNames(Type type, List<String> names) {
        if (type == null || type.isPrimitiveType() || type.isVoidType()) {
            return;
        }

        if (type.isArrayType()) {
            collectTypeNames(type.asArrayType().getComponentType(), names);
            return;
        }

        if (type.isClassOrInterfaceType()) {
            ClassOrInterfaceType cit = type.asClassOrInterfaceType();
            names.add(cit.getNameWithScope());
            cit.getTypeArguments().ifPresent(args -> {
                for (Type arg : args) {
                    collectTypeNames(arg, names);
                }
            });
            return;
        }

        if (type.isWildcardType()) {
            type.asWildcardType().getExtendedType().ifPresent(t -> collectTypeNames(t, names));
            type.asWildcardType().getSuperType().ifPresent(t -> collectTypeNames(t, names));
        }
    }
}
