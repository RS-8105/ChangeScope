package com.changescope.model;

import com.changescope.dto.TypeKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.Objects;

@Entity
@Table(
    name = "indexed_types",
    indexes = {
        @Index(name = "idx_indexed_types_index_fqn", columnList = "index_id, fully_qualified_name"),
        @Index(name = "idx_indexed_types_index_simple_name", columnList = "index_id, simple_name")
    }
)
public class IndexedTypeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "index_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private RepositoryIndexEntity index;

    @NotBlank(message = "Fully qualified name must not be blank")
    @Size(max = 500, message = "Fully qualified name cannot exceed 500 characters")
    @Column(name = "fully_qualified_name", nullable = false, length = 500)
    private String fullyQualifiedName;

    @NotBlank(message = "Simple name must not be blank")
    @Size(max = 200, message = "Simple name cannot exceed 200 characters")
    @Column(name = "simple_name", nullable = false, length = 200)
    private String simpleName;

    @Size(max = 300, message = "Package name cannot exceed 300 characters")
    @Column(name = "package_name", length = 300)
    private String packageName;

    @NotBlank(message = "File path must not be blank")
    @Size(max = 500, message = "File path cannot exceed 500 characters")
    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @NotNull(message = "Type kind must not be null")
    @Enumerated(EnumType.STRING)
    @Column(name = "type_kind", nullable = false, length = 30)
    private TypeKind typeKind;

    public IndexedTypeEntity() {
    }

    public IndexedTypeEntity(
        RepositoryIndexEntity index,
        String fullyQualifiedName,
        String simpleName,
        String packageName,
        String filePath,
        TypeKind typeKind
    ) {
        this.index = index;
        this.fullyQualifiedName = fullyQualifiedName;
        this.simpleName = simpleName;
        this.packageName = packageName;
        this.filePath = filePath;
        this.typeKind = typeKind;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public RepositoryIndexEntity getIndex() {
        return index;
    }

    public void setIndex(RepositoryIndexEntity index) {
        this.index = index;
    }

    public String getFullyQualifiedName() {
        return fullyQualifiedName;
    }

    public void setFullyQualifiedName(String fullyQualifiedName) {
        this.fullyQualifiedName = fullyQualifiedName;
    }

    public String getSimpleName() {
        return simpleName;
    }

    public void setSimpleName(String simpleName) {
        this.simpleName = simpleName;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public TypeKind getTypeKind() {
        return typeKind;
    }

    public void setTypeKind(TypeKind typeKind) {
        this.typeKind = typeKind;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IndexedTypeEntity that = (IndexedTypeEntity) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
