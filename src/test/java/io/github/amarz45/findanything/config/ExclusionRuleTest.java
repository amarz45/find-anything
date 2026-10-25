package io.github.amarz45.findanything.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ExclusionRuleTest {

    @Test
    void aNamePatternMatchesAtAnyDepthAndEverythingBelowIt() throws Exception {
        ExclusionRule rule = ExclusionRule.parse("node_modules");
        assertThat(rule.matches(Path.of("node_modules"))).isTrue();
        assertThat(rule.matches(Path.of("app", "node_modules", "left-pad", "index.js"))).isTrue();
        assertThat(rule.matches(Path.of("app", "src", "index.js"))).isFalse();
        assertThat(rule.matches(Path.of("my_node_modules"))).isFalse();
    }

    @Test
    void aWildcardPatternMatchesFileNames() throws Exception {
        ExclusionRule rule = ExclusionRule.parse("*.tmp");
        assertThat(rule.matches(Path.of("deep", "down", "scratch.tmp"))).isTrue();
        assertThat(rule.matches(Path.of("scratch.tmp.txt"))).isFalse();
        assertThat(ExclusionRule.parse("~$*").matches(Path.of("school", "~$report.docx"))).isTrue();
    }

    @Test
    void aPatternWithASlashMatchesTheRelativePath() throws Exception {
        ExclusionRule rule = ExclusionRule.parse("build/tmp");
        assertThat(rule.matches(Path.of("build", "tmp"))).isTrue();
        assertThat(rule.matches(Path.of("build", "tmp", "a.o"))).isTrue();
        assertThat(rule.matches(Path.of("other", "build", "tmp"))).isFalse();
        assertThat(ExclusionRule.parse("docs/**/*.bak").matches(Path.of("docs", "a", "b.bak")))
                .isTrue();
    }

    @Test
    void aLeadingSlashAnchorsANameAtTheRoot() throws Exception {
        ExclusionRule rule = ExclusionRule.parse("/build");
        assertThat(rule.matches(Path.of("build", "out.o"))).isTrue();
        assertThat(rule.matches(Path.of("src", "build", "out.o"))).isFalse();
        assertThat(rule.getPattern()).isEqualTo("/build");
    }

    @Test
    void rejectsEmptyAndCatchAllAndBrokenPatterns() {
        assertThatThrownBy(() -> ExclusionRule.parse("   "))
                .isInstanceOf(InvalidPatternException.class);
        assertThatThrownBy(() -> ExclusionRule.parse("*"))
                .isInstanceOf(InvalidPatternException.class)
                .hasMessageContaining("everything");
        assertThatThrownBy(() -> ExclusionRule.parse("**"))
                .isInstanceOf(InvalidPatternException.class);
        assertThatThrownBy(() -> ExclusionRule.parse("{a,b"))
                .isInstanceOf(InvalidPatternException.class)
                .hasMessageContaining("not valid");
    }

    @Test
    void rulesWithTheSamePatternAreEqual() throws Exception {
        assertThat(ExclusionRule.parse("*.tmp")).isEqualTo(ExclusionRule.parse("*.tmp"));
        assertThat(ExclusionRule.parse("*.tmp")).isNotEqualTo(ExclusionRule.parse("*.bak"));
    }
}
