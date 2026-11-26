package com.lowaltitude.inspection.support.infrastructure.persistence;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgreementMapper {
    @Select("""
            WITH current_document AS (
                SELECT id, title, version, published_on FROM platform_agreement
                WHERE doc = #{doc} AND published_on <= (now() AT TIME ZONE 'Asia/Shanghai')::date
                    AND EXISTS (SELECT 1 FROM platform_agreement_section WHERE agreement_id = platform_agreement.id)
                ORDER BY published_on DESC LIMIT 1
            )
            SELECT d.title, d.version, d.published_on, s.title AS section_title, s.body
            FROM current_document d JOIN platform_agreement_section s ON s.agreement_id = d.id
            ORDER BY s.position
            """)
    List<SectionRow> published(@Param("doc") String doc);

    record SectionRow(String title, String version, LocalDate publishedOn, String sectionTitle, String body) {}
}
