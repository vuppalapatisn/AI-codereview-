package com.example.codereview.service;

import com.example.codereview.config.ReviewProperties;
import com.example.codereview.model.ReviewModels.RepoConventions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Layers organization-wide policy on top of repository-specific convention
 * files. In production this would likely be backed by a config repo or a
 * small database table that repo owners can self-serve edit; here it reads
 * YAML files from disk so the composition logic is the reusable part.
 *
 * Expected layout:
 *   {repo-config-path}/default-conventions.yml   (org-wide, applies to all repos)
 *   {repo-config-path}/{owner}__{repo}.yml         (repo-specific overrides, optional)
 */
@Slf4j
@Component
public class RepoConventionService {

    private final ReviewProperties properties;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public RepoConventionService(ReviewProperties properties) {
        this.properties = properties;
    }

    @Cacheable(value = "repoConventions", key = "#owner + '/' + #repo")
    public RepoConventions loadConventions(String owner, String repo) {
        RepoConventions orgWide = loadYaml(properties.getConventions().getDefaultConfig());
        RepoConventions repoSpecific = loadYaml(owner + "__" + repo + ".yml");

        return RepoConventions.builder()
                .repoName(owner + "/" + repo)
                .orgWidePolicies(orgWide != null ? orgWide.getOrgWidePolicies() : java.util.List.of())
                .repoSpecificRules(repoSpecific != null ? repoSpecific.getRepoSpecificRules() : java.util.List.of())
                .ignoredCategories(repoSpecific != null ? repoSpecific.getIgnoredCategories() : java.util.List.of())
                .highRiskPaths(repoSpecific != null ? repoSpecific.getHighRiskPaths() : java.util.List.of())
                .build();
    }

    private RepoConventions loadYaml(String fileName) {
        Path configDir = Path.of(properties.getConventions().getRepoConfigPath());
        File file = configDir.resolve(fileName).toFile();
        if (!file.exists()) {
            return null;
        }
        try {
            return yamlMapper.readValue(file, RepoConventions.class);
        } catch (IOException e) {
            log.warn("Failed to parse conventions file {}: {}", file, e.getMessage());
            return null;
        }
    }
}
