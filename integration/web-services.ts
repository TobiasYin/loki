/** Service-specific wire formats. Credentials are installed by Native only. */
export function webRequest(backend: string, search: boolean, endpoint: string, args: { query?: string; url?: string }) {
  if (search && backend === 'DeepSeek' && Array.from(args.query ?? '').length > 128) throw new Error('DeepSeek 搜索词最多 128 个字符，请缩短查询');
  if (search && backend === '豆包搜索' && (Array.from(args.query?.trim() ?? '').length < 1 || Array.from(args.query?.trim() ?? '').length > 100)) throw new Error('豆包搜索词需为 1–100 个字符，请缩短查询');
  const post = backend === 'DeepSeek' || (search && (backend === 'Tavily' || backend === '豆包搜索'));
  return {
    url: search && !post ? `${endpoint}?q=${encodeURIComponent(args.query!)}&count=5` : endpoint,
    init: post ? { method: 'POST', headers: { accept: 'application/json', 'content-type': 'application/json' }, body: JSON.stringify(search ? backend === '豆包搜索' ? { Query: args.query?.trim(), Count: 5, SearchType: 'web' } : backend === 'Tavily' ? { query: args.query, max_results: 5, include_answer: false } : { query: args.query, count: 5 } : { url: args.url }) }
      : { method: 'GET', headers: { accept: search ? 'application/json' : 'text/html,text/plain' } },
  };
}
export function searchResults(backend: string, text: string) {
  const data = JSON.parse(text);
  if (backend === '豆包搜索' && data.ResponseMetadata?.Error) throw new Error('豆包搜索调用失败，请检查套餐权限与抵扣开关');
  const results = backend === '豆包搜索' ? data.Result?.WebResults : backend === 'Brave' ? data.web?.results : data.results;
  if (!Array.isArray(results)) throw new Error('搜索服务返回格式错误');
  return results.slice(0, 5).map((r: any) => (backend === '豆包搜索' ? {title: r.Title, url: r.Url, summary: r.Summary || r.Snippet || r.Content, sourceTime: r.PublishTime ?? null} : { title: r.title, url: r.url, summary: backend === 'Brave' ? r.description : backend === 'Tavily' ? r.content : r.summary || r.snippet, sourceTime: r.date ?? r.published_date ?? r.page_age ?? null }));
}
