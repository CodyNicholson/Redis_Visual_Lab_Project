import { CatalogVisual, SpringCacheVisual } from './CachingVisuals.jsx';
import {
  CounterVisual, HashesVisual, LeaderboardVisual, ListsVisual, SetsVisual, StringsVisual, TtlVisual,
} from './CoreVisuals.jsx';
import { LockVisual, LuaVisual, PipelineVisual, RateLimitVisual, TransactionsVisual } from './PatternVisuals.jsx';
import { PubSubVisual, StreamsVisual } from './MessagingVisuals.jsx';
import { BitmapsVisual, BloomVisual, GeoVisual, HyperLogLogVisual } from './ProbabilisticVisuals.jsx';
import { JsonVisual, SearchVisual, ServerVisual, TimeSeriesVisual, VectorsVisual } from './ModuleVisuals.jsx';

// Maps each demo's `visual` name (see demos/catalog.js) to the component that draws its data
export const VISUALS = {
  catalog: CatalogVisual,
  springCache: SpringCacheVisual,
  strings: StringsVisual,
  counter: CounterVisual,
  ttl: TtlVisual,
  hashes: HashesVisual,
  lists: ListsVisual,
  sets: SetsVisual,
  leaderboard: LeaderboardVisual,
  ratelimit: RateLimitVisual,
  lock: LockVisual,
  transactions: TransactionsVisual,
  lua: LuaVisual,
  pipeline: PipelineVisual,
  pubsub: PubSubVisual,
  streams: StreamsVisual,
  hyperloglog: HyperLogLogVisual,
  bitmaps: BitmapsVisual,
  bloom: BloomVisual,
  geo: GeoVisual,
  json: JsonVisual,
  search: SearchVisual,
  timeseries: TimeSeriesVisual,
  vectors: VectorsVisual,
  server: ServerVisual,
};
