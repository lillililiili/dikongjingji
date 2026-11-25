package com.lowaltitude.inspection.experience.application.ops;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PlayGameOpsServiceTest {
    @Test
    void acceptsBothPreviouslyPublishedBoardsAndAllowsPassingThroughTheGoal() {
        for (String board : new String[]{"..S...WWW.S..W..W.W...S.G", "....S.W.W..WSW....W.S...G",
                ".GSWW" + "WWWWW".repeat(4)}) {
            assertThat(PlayGameOpsService.boardProblem(board)).isNull();
        }
    }

    @Test
    void rejectsMalformedBoardsAndMissingOrDuplicateTargets() {
        assertThat(PlayGameOpsService.boardProblem("x")).isNotNull();
        assertThat(PlayGameOpsService.boardProblem("WSG"+".".repeat(22))).contains("起点");
        assertThat(PlayGameOpsService.boardProblem(".SGG"+".".repeat(21))).contains("一个终点");
        assertThat(PlayGameOpsService.boardProblem(".S"+".".repeat(23))).contains("一个终点");
        assertThat(PlayGameOpsService.boardProblem(".G"+".".repeat(23))).contains("星星");
    }

    @Test
    void requiresTheGoalAndEveryStarToBeReachableWithoutMovingAcrossRowEdges() {
        assertThat(PlayGameOpsService.boardProblem(".S..G" + "WWWWW" + "S...." + ".....".repeat(2))).contains("星星无法到达");
        assertThat(PlayGameOpsService.boardProblem(".S..." + "WWWWW" + "G...." + ".....".repeat(2))).contains("终点无法");
        assertThat(PlayGameOpsService.boardProblem(".S..." + "GWWWW" + "WWWWW".repeat(3))).isNull();
        assertThat(PlayGameOpsService.boardProblem(".S..G" + "WWWWW" + "....S" + "WWWWW".repeat(2))).contains("星星无法到达");
    }
}
