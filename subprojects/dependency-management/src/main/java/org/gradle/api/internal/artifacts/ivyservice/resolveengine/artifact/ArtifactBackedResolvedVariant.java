/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact;

import org.gradle.api.Buildable;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet.AsyncArtifactListener;
import org.gradle.api.internal.attributes.AttributeContainerInternal;
import org.gradle.internal.DisplayName;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationQueue;
import org.gradle.internal.operations.RunnableBuildOperation;
import org.gradle.internal.progress.BuildOperationDescriptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet.EMPTY;

class ArtifactBackedResolvedVariant implements ResolvedVariant {
    private final DisplayName displayName;
    private final AttributeContainerInternal attributes;
    private final ResolvedArtifactSet artifacts;

    private ArtifactBackedResolvedVariant(DisplayName displayName, AttributeContainerInternal attributes, ResolvedArtifactSet artifacts) {
        this.displayName = displayName;
        this.attributes = attributes;
        this.artifacts = artifacts;
    }

    public static ResolvedVariant create(DisplayName displayName, AttributeContainerInternal attributes, Collection<? extends ResolvedArtifact> artifacts) {
        if (artifacts.isEmpty()) {
            return new ArtifactBackedResolvedVariant(displayName, attributes, EMPTY);
        }
        if (artifacts.size() == 1) {
            return new ArtifactBackedResolvedVariant(displayName, attributes, new SingleArtifactSet(attributes, artifacts.iterator().next()));
        }
        List<SingleArtifactSet> artifactSets = new ArrayList<SingleArtifactSet>();
        for (ResolvedArtifact artifact : artifacts) {
            artifactSets.add(new SingleArtifactSet(attributes, artifact));
        }
        return new ArtifactBackedResolvedVariant(displayName, attributes, CompositeArtifactSet.of(artifactSets));
    }

    @Override
    public DisplayName asDescribable() {
        return displayName;
    }

    @Override
    public ResolvedArtifactSet getArtifacts() {
        return artifacts;
    }

    @Override
    public AttributeContainerInternal getAttributes() {
        return attributes;
    }

    private static boolean isFromIncludedBuild(ResolvedArtifact artifact) {
        ComponentIdentifier id = artifact.getId().getComponentIdentifier();
        return id instanceof ProjectComponentIdentifier
            && !((ProjectComponentIdentifier) id).getBuild().isCurrentBuild();
    }

    private static class SingleArtifactSet implements ResolvedArtifactSet, ResolvedArtifactSet.Completion {
        private final AttributeContainer variantAttributes;
        private final ResolvedArtifact artifact;
        private volatile Throwable failure;

        SingleArtifactSet(AttributeContainer variantAttributes, ResolvedArtifact artifact) {
            this.variantAttributes = variantAttributes;
            this.artifact = artifact;
        }

        @Override
        public Completion startVisit(BuildOperationQueue<RunnableBuildOperation> actions, AsyncArtifactListener listener) {
            if (listener.requireArtifactFiles() && !isFromIncludedBuild(artifact))  {
                actions.add(new DownloadArtifactFile(artifact, this, listener));
            }
            return this;
        }

        @Override
        public void visit(ArtifactVisitor visitor) {
            if (failure != null) {
                visitor.visitFailure(failure);
            } else {
                visitor.visitArtifact(variantAttributes, artifact);
            }
        }

        @Override
        public void collectBuildDependencies(BuildDependenciesVisitor visitor) {
            visitor.visitDependency(((Buildable) artifact).getBuildDependencies());
        }
    }

    private static class DownloadArtifactFile implements RunnableBuildOperation {
        private final ResolvedArtifact artifact;
        private final SingleArtifactSet owner;
        private final AsyncArtifactListener listener;

        DownloadArtifactFile(ResolvedArtifact artifact, SingleArtifactSet owner, AsyncArtifactListener visitor) {
            this.artifact = artifact;
            this.owner = owner;
            this.listener = visitor;
        }

        @Override
        public void run(BuildOperationContext context) {
            try {
                artifact.getFile();
                listener.artifactAvailable(artifact);
            } catch (Throwable t) {
                owner.failure = t;
            }
        }

        @Override
        public BuildOperationDescriptor.Builder description() {
            return BuildOperationDescriptor.displayName("Resolve artifact " + artifact)
                .details(new DownloadArtifactBuildOperationDetails(artifact.getId().getDisplayName()));
        }
    }

}
