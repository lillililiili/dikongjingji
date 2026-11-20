import type { ContentArticleCard, ContentFeedPage } from '../../content/domain/content'

// The 推荐 channel's "城市资讯" block (HomeFull board): one article the city's operations centre
// published, read from the discover page's public feed (M19). Merchants' and providers' articles
// stay on the discover page; when the city has no platform article the block is left out.

export const CITY_NEWS_FEED_SIZE = 10

export function pickCityNews(feed: Pick<ContentFeedPage, 'items'>): ContentArticleCard | null {
  // The feed mixes in videos (content 1.2.0); the block opens an article page, so only articles count.
  return feed.items.find((article) => article.kind !== 'VIDEO' && article.author.kind === 'PLATFORM' && !article.sponsored) ?? null
}

// "东营资讯 · 东营城市运营中心"
export function formatCityNewsSource(article: ContentArticleCard, shortCity: string): string {
  return [shortCity ? `${shortCity}资讯` : '城市资讯', article.author.name]
    .filter(Boolean)
    .join(' · ')
}

/**
 * Loads the article for a city. A newer load drops the answer of an older one, so the previous
 * city's article never shows up under the new city. A failed request leaves the block out.
 */
export function createCityNewsLoader(
  source: { loadFeed(channel: 'RECOMMEND', cityCode: string, page: number, size: number): Promise<ContentFeedPage> },
  options: { setArticle(article: ContentArticleCard | null): void },
): { load(cityCode: string): Promise<void> } {
  let revision = 0

  return {
    async load(cityCode) {
      const requested = ++revision
      options.setArticle(null)
      try {
        const feed = await source.loadFeed('RECOMMEND', cityCode, 1, CITY_NEWS_FEED_SIZE)
        if (requested === revision) options.setArticle(pickCityNews(feed))
      } catch {
        // Already empty.
      }
    },
  }
}
