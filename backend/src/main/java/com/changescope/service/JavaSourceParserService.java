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
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
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

            List<ParsedType> types = new ArrayList<>();
            for (TypeDeclaration<?> typeDecl : cu.findAll(TypeDeclaration.class)) {
                if (typeDecl instanceof ClassOrInterfaceDeclaration cid) {
                    types.add(extractClassOrInterface(cid));
                } else if (typeDecl instanceof RecordDeclaration rd) {
                    types.add(extractRecord(rd));
                } else if (typeDecl instanceof EnumDeclaration ed) {
                    types.add(extractEnum(ed));
                }
            }

            return ParsedJavaFile.success(packageName, imports, types);
        } catch (Exception ex) {
            return ParsedJavaFile.error("Unexpected error during parsing: " + ex.getMessage());
        }
    }

    private ParsedType extractClassOrInterface(ClassOrInterfaceDeclaration declaration) {
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

        return new ParsedType(
            name,
            kind,
            extendedTypes,
            implementedTypes,
            fields,
            methods,
            constructors,
            List.of(),
            List.of()
        );
    }

    private ParsedType extractRecord(RecordDeclaration declaration) {
        String name = declaration.getNameAsString();
        TypeKind kind = TypeKind.RECORD;

        List<String> extendedTypes = List.of();

        List<String> implementedTypes = declaration.getImplementedTypes().stream()
            .flatMap(t -> extractTypeNames(t).stream())
            .toList();

        List<ParsedField> recordComponents = declaration.getParameters().stream()
            .map(p -> new ParsedField(
                p.getTypeAsString(),
                p.getNameAsString(),
                extractTypeNames(p.getType())
            ))
            .toList();

        List<ParsedField> bodyFields = declaration.getFields().stream()
            .flatMap(f -> extractFields(f).stream())
            .toList();

        List<ParsedField> allFields = new ArrayList<>(recordComponents);
        allFields.addAll(bodyFields);

        List<ParsedConstructor> constructors = new ArrayList<>();
        declaration.getConstructors().forEach(ctor -> constructors.add(extractConstructor(ctor)));
        declaration.getCompactConstructors().forEach(ctor -> constructors.add(extractCompactConstructor(ctor)));

        List<ParsedMethod> methods = declaration.getMethods().stream()
            .map(this::extractMethod)
            .toList();

        return new ParsedType(
            name,
            kind,
            extendedTypes,
            implementedTypes,
            allFields,
            methods,
            constructors,
            recordComponents,
            List.of()
        );
    }

    private ParsedConstructor extractCompactConstructor(CompactConstructorDeclaration constructor) {
        String name = constructor.getNameAsString();
        List<String> referencedTypes = constructor.getThrownExceptions().stream()
            .flatMap(t -> extractTypeNames(t).stream())
            .toList();

        return new ParsedConstructor(name, List.of(), List.of(), referencedTypes);
    }

    private ParsedType extractEnum(EnumDeclaration declaration) {
        String name = declaration.getNameAsString();
        TypeKind kind = TypeKind.ENUM;

        List<String> extendedTypes = List.of();

        List<String> implementedTypes = declaration.getImplementedTypes().stream()
            .flatMap(t -> extractTypeNames(t).stream())
            .toList();

        List<String> enumConstants = declaration.getEntries().stream()
            .map(EnumConstantDeclaration::getNameAsString)
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

        return new ParsedType(
            name,
            kind,
            extendedTypes,
            implementedTypes,
            fields,
            methods,
            constructors,
            List.of(),
            enumConstants
        );
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
