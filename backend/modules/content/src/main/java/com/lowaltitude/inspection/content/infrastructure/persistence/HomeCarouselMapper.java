package com.lowaltitude.inspection.content.infrastructure.persistence;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface HomeCarouselMapper {

    @Select("""
            SELECT id, tag, title_first, title_second, description_first, description_second,
                   action_label, image_ref, image_alt, tone, target
            FROM content_home_slide
            WHERE city_code = #{city} AND status = 'PUBLISHED'
            ORDER BY sort_order, id
            LIMIT 12
            """)
    List<SlideRow> published(@Param("city") String city);

    record SlideRow(String id, String tag, String titleFirst, String titleSecond,
                    String descriptionFirst, String descriptionSecond, String actionLabel,
                    String imageRef, String imageAlt, String tone, String target) {
    }
}
