package com.changescope.model;

import com.changescope.dto.RelationshipKind;
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
    name = "indexed_type_dependencies",
    indexes = {
        @Index(name = "idx_indexed_type_deps_index_target", columnList = "index_id, target_type"),
        @Index(name = "idx_indexed_type_deps_index_source", columnList = "index_id, source_type")
    }
)
public class IndexedTypeDependencyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "index_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private RepositoryIndexEntity index;

    @NotBlank(message = "Source type must not be blank")
    @Size(max = 500, message = "Source type cannot exceed 500 characters")
    @Column(name = "source_type", nullable = false, length = 500)
    private String sourceType;

    @NotBlank(message = "Target type must not be blank")
    @Size(max = 500, message = "Target type cannot exceed 500 characters")
    @Column(name = "target_type", nullable = false, length = 500)
    private String targetType;

    @NotNull(message = "Relationship kind must not be null")
    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_kind", nullable = false, length = 50)
    private RelationshipKind relationshipKind;

    @Size(max = 500, message = "Source file path cannot exceed 500 characters")
    @Column(name = "source_file_path", length = 500)
    private String sourceFilePath;

    public IndexedTypeDependencyEntity() {
    }

    public IndexedTypeDependencyEntity(
        RepositoryIndexEntity index,
        String sourceType,
        String targetType,
        RelationshipKind relationshipKind,
        String sourceFilePath
    ) {
        this.index = index;
        this.sourceType = sourceType;
        this.targetType = targetType;
        this.relationshipKind = relationshipKind;
        this.sourceFilePath = sourceFilePath;
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

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public RelationshipKind getRelationshipKind() {
        return relationshipKind;
    }

    public void setRelationshipKind(RelationshipKind relationshipKind) {
        this.relationshipKind = relationshipKind;
    }

    public String getSourceFilePath() {
        return sourceFilePath;
    }

    public void setSourceFilePath(String sourceFilePath) {
        this.sourceFilePath = sourceFilePath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IndexedTypeDependencyEntity that = (IndexedTypeDependencyEntity) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
