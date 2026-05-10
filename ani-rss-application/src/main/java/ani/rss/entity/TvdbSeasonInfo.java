package ani.rss.entity;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@Accessors(chain = true)
public class TvdbSeasonInfo implements Serializable {
    private String tvdbId;
    private Integer tvdbSeason;
    private Integer tvdbEpisodeOffset;
}
