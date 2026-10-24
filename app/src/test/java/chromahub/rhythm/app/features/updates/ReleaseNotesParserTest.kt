// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.updates

import org.junit.Assert.*
import org.junit.Test

class ReleaseNotesParserTest {
    @Test fun koreanReleaseWithoutEnglishHeadingsShowsTheActualChanges() {
        val result = ReleaseNotesParser.parse("1325 업데이트입니다.\n\n- QR 연결 지원\n- 장르 자체 검색\n- 이전 검색어 보존")
        assertEquals(listOf("QR 연결 지원", "장르 자체 검색", "이전 검색어 보존"), result.whatsNew)
        assertTrue(result.knownIssues.isEmpty())
    }
    @Test fun generatedLegacyFormatSeparatesIssuesAndBuildInformation() {
        val result = ReleaseNotesParser.parse("**What's New:**\n- **Fixed:** 재생 위치\n**Known Issues (Will be fixed on a later build):**\n- 카메라 실기기 확인\n**Build Information:**\n- Internal build details")
        assertEquals(listOf("<b>Fixed:</b> 재생 위치"), result.whatsNew)
        assertEquals(listOf("카메라 실기기 확인"), result.knownIssues)
    }
    @Test fun markdownKoreanSectionsKeepIssuesOutOfChanges() {
        val result = ReleaseNotesParser.parse("## 변경 사항\n- 재생 안정성\n## 알려진 문제\n- 테스트 기기 제한\n## 검증 결과\n- 88 tests")
        assertEquals(listOf("재생 안정성"), result.whatsNew)
        assertEquals(listOf("테스트 기기 제한"), result.knownIssues)
    }
    @Test fun narrativeNotesStayVisibleAndAreNotInterpretedAsHtml() {
        assertEquals(listOf("재생 &lt;오류&gt; 수정 &amp; 개선"), ReleaseNotesParser.parse("재생 <오류> 수정 & 개선").whatsNew)
        assertTrue(ReleaseNotesParser.parse(null).whatsNew.isEmpty())
    }
}
