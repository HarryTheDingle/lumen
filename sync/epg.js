// UK TV Guide (EPG) ingestion, canonical channel mapping, sport tagging, TMDB enrichment, and query API for Lumos.
// Fetches 7-day Sky UK linear schedules with Freeview and optional XMLTV fallbacks, keeps a rolling window in D1,
// and serves catch-up + live + upcoming listings via POST /epg/uk.

import { tmdbGet } from "./tmdb.js";

const HOUR_MS = 3600 * 1000;
const DAY_MS = 24 * HOUR_MS;
const REFRESH_TTL_MS = 12 * HOUR_MS;
const PAST_INGEST_WINDOW_MS = 72 * HOUR_MS;
const FUTURE_INGEST_WINDOW_MS = 7 * DAY_MS;
const DEFAULT_PAST_MS = 24 * HOUR_MS;
const DEFAULT_FUTURE_MS = 120 * HOUR_MS;
const MAX_READ_SPAN_MS = 7 * DAY_MS;
const RETENTION_MS = 7 * DAY_MS;
const ENRICH_TTL_MS = 30 * DAY_MS;
const PROG_ROW_CHUNK = 8; // 8 rows * 10 cols = 80 params, safely under D1's 100-parameter limit
const BATCH_CHUNK = 50;
const SKY_SCHEDULE_BASE = "https://awk.epgsky.com/hawk/linear/schedule";
const SKY_SID_CHUNK = 20;
const FREEVIEW_BASE = "https://www.freeview.co.uk/api/tv-guide?nid=64257";
const META_LAST_KEY = "epg:uk:last";

const CHANNEL_DEFS = [
  // Core UK terrestrial & public service (numbers and aliases aligned with UkChannelRegistry)
  { id: "uk.bbc.one", name: "BBC One", category: "Entertainment", number: 101, freeviewSid: 4164, skySid: 3000, mainGuide: true, sportsGuide: false, aliases: ["bbc1", "bbc 1", "bbc one hd", "bbc one lon", "bbc 1 lon", "bbc one london", "bbc one england", "bbc one south", "bbc one wales", "bbc one ni", "bbc1.uk", "bbcone.uk", "bbc1.london.uk", "17540"] },
  { id: "uk.bbc.two", name: "BBC Two", category: "Entertainment", number: 102, freeviewSid: 4287, skySid: 2075, mainGuide: true, sportsGuide: false, aliases: ["bbc2", "bbc 2", "bbc two hd", "bbc two england", "bbc two wales", "bbc two ni", "bbc2.uk", "bbctwo.uk", "17472"] },
  { id: "uk.itv.one", name: "ITV1", category: "Entertainment", number: 103, freeviewSid: 8261, skySid: 6504, mainGuide: true, sportsGuide: false, aliases: ["itv", "itv 1", "itv1 hd", "itv london", "itv1 london", "itv.uk", "itv1.uk", "17608"] },
  { id: "uk.ch4", name: "Channel 4", category: "Entertainment", number: 104, freeviewSid: 8384, skySid: 4075, mainGuide: true, sportsGuide: false, aliases: ["c4", "ch 4", "ch4", "channel4", "channel 4 hd", "channel4.uk", "ch4.uk", "c4.uk", "17664"] },
  { id: "uk.ch5", name: "Channel 5", category: "Entertainment", number: 105, freeviewSid: 8500, skySid: 4058, mainGuide: true, sportsGuide: false, aliases: ["c5", "ch 5", "ch5", "five", "channel5", "channel 5 hd", "channel5.uk", "ch5.uk", "c5.uk", "17728"] },
  { id: "uk.itv.two", name: "ITV2", category: "Entertainment", number: 106, freeviewSid: 8325, skySid: 6532, mainGuide: true, sportsGuide: false, aliases: ["itv 2", "itv2 hd", "itv2.uk"] },
  { id: "uk.bbc.three", name: "BBC Three", category: "Entertainment", number: 107, freeviewSid: 4351, skySid: 2080, mainGuide: true, sportsGuide: false, aliases: ["bbc3", "bbc 3", "bbc three hd", "bbc3.uk", "bbcthree.uk", "17920"] },
  { id: "uk.bbc.four", name: "BBC Four", category: "Entertainment", number: 108, freeviewSid: 4544, skySid: 2086, mainGuide: true, sportsGuide: false, aliases: ["bbc4", "bbc 4", "bbc four hd", "bbc4.uk", "bbcfour.uk", "17603"] },
  { id: "uk.itv.three", name: "ITV3", category: "Entertainment", number: 109, freeviewSid: 8294, skySid: 6533, mainGuide: true, sportsGuide: false, aliases: ["itv 3", "itv3 hd", "itv3.uk"] },
  { id: "uk.itv.four", name: "ITV4", category: "Entertainment", number: 110, freeviewSid: 8353, skySid: 6534, mainGuide: true, sportsGuide: false, aliases: ["itv 4", "itv4 hd", "itv4.uk"] },
  { id: "uk.e4", name: "E4", category: "Entertainment", number: 111, freeviewSid: 8448, skySid: 4076, mainGuide: true, sportsGuide: false, aliases: ["e4 hd", "e4 uk", "e4.uk"] },
  { id: "uk.more4", name: "More4", category: "Entertainment", number: 112, freeviewSid: 8442, skySid: 4043, mainGuide: true, sportsGuide: false, aliases: ["more 4", "more4 hd", "more4.uk"] },
  { id: "uk.film4", name: "Film4", category: "Movies", number: 113, freeviewSid: 8452, skySid: 4044, mainGuide: true, sportsGuide: false, aliases: ["film 4", "film4 hd", "film4.uk"] },
  { id: "uk.itvbe", name: "ITVBe", category: "Entertainment", number: 114, freeviewSid: 8361, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["itv be", "itvbe hd", "itvbe.uk"] },
  { id: "uk.5star", name: "5STAR", category: "Entertainment", number: 115, freeviewSid: 12992, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["5 star", "five star", "5star.uk"] },
  { id: "uk.5usa", name: "5USA", category: "Entertainment", number: 116, freeviewSid: 12928, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["5 usa", "five usa", "5usa.uk"] },
  { id: "uk.5action", name: "5ACTION", category: "Entertainment", number: 117, freeviewSid: 27136, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["5 action", "paramount network", "paramount network uk", "5action.uk"] },
  { id: "uk.5select", name: "5SELECT", category: "Entertainment", number: 118, freeviewSid: 16048, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["5 select", "5select.uk"] },
  { id: "uk.dave", name: "Dave", category: "Entertainment", number: 119, freeviewSid: 22272, skySid: 3809, mainGuide: true, sportsGuide: false, aliases: ["u&dave", "u & dave", "u and dave", "uktv dave", "dave hd", "dave.uk", "udave.uk"] },
  { id: "uk.drama", name: "Drama", category: "Entertainment", number: 120, freeviewSid: 22336, skySid: 2612, mainGuide: true, sportsGuide: false, aliases: ["u&drama", "u & drama", "u and drama", "uktv drama", "drama.uk", "udrama.uk"] },
  { id: "uk.yesterday", name: "Yesterday", category: "Documentary", number: 121, freeviewSid: 25792, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["u&yesterday", "u & yesterday", "u and yesterday", "uktv yesterday", "yesterday.uk", "uyesterday.uk"] },
  { id: "uk.w", name: "W", category: "Entertainment", number: 122, freeviewSid: 22464, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["u&w", "u & w", "u and w", "uktv w", "watch", "w hd", "w.uk", "uw.uk"] },
  { id: "uk.alibi", name: "Alibi", category: "Entertainment", number: 123, freeviewSid: null, skySid: 3825, mainGuide: true, sportsGuide: false, aliases: ["u&alibi", "u & alibi", "u and alibi", "uktv alibi", "alibi hd", "alibi.uk", "ualibi.uk"] },
  { id: "uk.gold", name: "GOLD", category: "Entertainment", number: 124, freeviewSid: null, skySid: 6513, mainGuide: true, sportsGuide: false, aliases: ["u&gold", "u & gold", "u and gold", "uktv gold", "gold uk", "gold hd", "gold.uk", "ugold.uk"] },
  { id: "uk.eden", name: "Eden", category: "Documentary", number: 125, freeviewSid: 24448, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["u&eden", "u & eden", "u and eden", "uktv eden", "eden hd", "eden.uk", "ueden.uk"] },
  { id: "uk.bbc.scotland", name: "BBC Scotland", category: "Entertainment", number: 126, freeviewSid: 4220, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["bbc scotland hd", "bbc1 scotland", "bbc one scotland", "bbcscotland.uk"] },
  { id: "uk.stv", name: "STV", category: "Entertainment", number: 127, freeviewSid: 8225, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["stv hd", "stv central", "stv north", "stv scotland", "stv.uk"] },
  { id: "uk.utv", name: "UTV", category: "Entertainment", number: 128, freeviewSid: 8228, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["utv hd", "utv northern ireland", "itv1 utv", "utv.uk"] },
  { id: "uk.s4c", name: "S4C", category: "Entertainment", number: 129, freeviewSid: 7168, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["s4c hd", "s4c wales", "s4c.uk"] },
  { id: "uk.bbc.alba", name: "BBC Alba", category: "Entertainment", number: 130, freeviewSid: 6720, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["alba", "bbc alba hd", "bbcalba.uk"] },
  { id: "uk.4seven", name: "4seven", category: "Entertainment", number: 131, freeviewSid: 8458, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["4 seven", "channel 4 seven", "4seven.uk"] },
  { id: "uk.e4.extra", name: "E4 Extra", category: "Entertainment", number: 132, freeviewSid: 27392, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["e4x", "e4extra", "e4 extra.uk", "e4extra.uk"] },
  { id: "uk.really", name: "Really", category: "Entertainment", number: 133, freeviewSid: 24192, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["really uk", "really.uk"] },
  { id: "uk.quest", name: "Quest", category: "Documentary", number: 134, freeviewSid: 27200, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["quest uk", "quest hd", "quest.uk"] },
  { id: "uk.quest.red", name: "Quest Red", category: "Entertainment", number: 135, freeviewSid: 27456, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["questred", "quest red.uk", "questred.uk"] },
  { id: "uk.blaze", name: "BLAZE", category: "Entertainment", number: 136, freeviewSid: 27424, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["blaze uk", "blaze.uk"] },
  { id: "uk.challenge", name: "Challenge", category: "Entertainment", number: 137, freeviewSid: 22144, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["challenge tv", "challenge.uk"] },
  { id: "uk.sky.mix", name: "Sky Mix", category: "Entertainment", number: 138, freeviewSid: 22048, skySid: 1831, mainGuide: true, sportsGuide: false, aliases: ["sky mix hd", "pick", "pick tv", "skymix.uk", "pick.uk"] },
  { id: "uk.sky.arts", name: "Sky Arts", category: "Entertainment", number: 139, freeviewSid: 22016, skySid: 4063, mainGuide: true, sportsGuide: false, aliases: ["sky arts 1", "sky arts hd", "skyarts.uk"] },
  { id: "uk.great.tv", name: "GREAT! tv", category: "Entertainment", number: 140, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["great tv", "sony channel", "greattv.uk"] },
  { id: "uk.great.movies", name: "GREAT! movies", category: "Movies", number: 141, freeviewSid: 27712, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["great movies", "greatmovies.uk"] },
  { id: "uk.great.action", name: "GREAT! action", category: "Movies", number: 142, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["great action", "movies4men", "greataction.uk"] },
  { id: "uk.talking.pictures", name: "Talking Pictures TV", category: "Movies", number: 143, freeviewSid: 27168, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["talking pictures", "tptv", "talkingpicturestv.uk"] },
  { id: "uk.legend", name: "LEGEND", category: "Movies", number: 144, freeviewSid: 28000, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["legend tv", "horror channel", "legend.uk"] },

  // Sky & Entertainment
  { id: "uk.sky.showcase", name: "Sky Showcase", category: "Entertainment", number: 201, freeviewSid: null, skySid: 4061, mainGuide: true, sportsGuide: false, aliases: ["sky showcase hd", "sky one", "sky 1", "sky1", "skyshowcase.uk", "skyone.uk"] },
  { id: "uk.sky.max", name: "Sky Max", category: "Entertainment", number: 202, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["sky max hd", "skymax.uk"] },
  { id: "uk.sky.atlantic", name: "Sky Atlantic", category: "Entertainment", number: 203, freeviewSid: null, skySid: 4053, mainGuide: true, sportsGuide: false, aliases: ["sky atlantic hd", "skyatlantic.uk"] },
  { id: "uk.sky.witness", name: "Sky Witness", category: "Entertainment", number: 204, freeviewSid: null, skySid: 4066, mainGuide: true, sportsGuide: false, aliases: ["sky witness hd", "sky living", "living", "skywitness.uk"] },
  { id: "uk.sky.comedy", name: "Sky Comedy", category: "Entertainment", number: 205, freeviewSid: null, skySid: 1143, mainGuide: true, sportsGuide: false, aliases: ["sky comedy hd", "skycomedy.uk"] },
  { id: "uk.sky.scifi", name: "Sky Sci-Fi", category: "Entertainment", number: 206, freeviewSid: null, skySid: 4074, mainGuide: true, sportsGuide: false, aliases: ["sky scifi", "sky sci fi", "syfy", "syfy uk", "skyscifi.uk", "syfy.uk"] },
  { id: "uk.sky.crime", name: "Sky Crime", category: "Entertainment", number: 207, freeviewSid: null, skySid: 1212, mainGuide: true, sportsGuide: false, aliases: ["sky crime hd", "real lives", "skycrime.uk"] },
  { id: "uk.sky.replay", name: "Sky Replay", category: "Entertainment", number: 208, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["sky two", "sky 2", "sky2", "skyreplay.uk"] },
  { id: "uk.comedy.central", name: "Comedy Central", category: "Entertainment", number: 209, freeviewSid: null, skySid: 4056, mainGuide: true, sportsGuide: false, aliases: ["comedy central uk", "comedy central hd", "comedycentral.uk"] },
  { id: "uk.comedy.central.extra", name: "Comedy Central Extra", category: "Entertainment", number: 210, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["comedy central xtra", "comedycentralextra.uk"] },
  { id: "uk.mtv.uk", name: "MTV UK", category: "Entertainment", number: 211, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["mtv", "mtv hd", "mtv.uk", "mtvuk.uk"] },
  { id: "uk.e.entertainment", name: "E! Entertainment", category: "Entertainment", number: 212, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["e!", "e entertainment", "e! uk", "eentertainment.uk"] },
  { id: "uk.tlc", name: "TLC", category: "Entertainment", number: 213, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["tlc uk", "tlc hd", "tlc.uk"] },
  { id: "uk.crime.investigation", name: "Crime + Investigation", category: "Entertainment", number: 214, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["crime & investigation", "crime and investigation", "crimeinvestigation.uk"] },
  { id: "uk.dmax", name: "DMAX", category: "Entertainment", number: 222, freeviewSid: 27264, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["dmax uk", "dmax.uk"] },
  { id: "uk.food.network", name: "Food Network", category: "Entertainment", number: 223, freeviewSid: 27360, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["food network uk", "foodnetwork.uk"] },
  { id: "uk.hgtv.uk", name: "HGTV", category: "Entertainment", number: 224, freeviewSid: 27328, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["hgtv uk", "hgtv.uk"] },
  { id: "uk.id.uk", name: "ID", category: "Entertainment", number: 225, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["investigation discovery", "id uk", "id.uk"] },
  { id: "uk.true.crime", name: "TRUE CRIME", category: "Entertainment", number: 226, freeviewSid: 27584, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["true crime uk", "cbs justice", "truecrime.uk"] },
  { id: "uk.reality.xtra", name: "Legend Xtra", category: "Entertainment", number: 227, freeviewSid: 27648, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["realityxtra", "cbs reality", "legendxtra.uk"] },
  { id: "uk.thatstv", name: "That's TV", category: "Entertainment", number: 228, freeviewSid: 27936, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["thats tv", "that's tv uk", "thatstv.uk"] },

  // Sports
  { id: "uk.sky.sports.main", name: "Sky Sports Main Event", category: "Sports", number: 301, freeviewSid: null, skySid: 4002, mainGuide: true, sportsGuide: true, aliases: ["sky sports main event hd", "sky sports me", "sky sports main", "ss main event", "skysportsmainevent.uk", "skysportsme.uk"] },
  { id: "uk.sky.sports.pl", name: "Sky Sports Premier League", category: "Sports", number: 302, freeviewSid: null, skySid: 4010, mainGuide: true, sportsGuide: true, aliases: ["sky sports premier league hd", "sky sports pl", "sky sports prem", "sky sports epl", "ss premier league", "skysportspremierleague.uk", "skysportspl.uk"] },
  { id: "uk.sky.sports.football", name: "Sky Sports Football", category: "Sports", number: 303, freeviewSid: null, skySid: 3939, mainGuide: true, sportsGuide: true, aliases: ["sky sports football hd", "ss football", "skysportsfootball.uk"] },
  { id: "uk.sky.sports.cricket", name: "Sky Sports Cricket", category: "Sports", number: 304, freeviewSid: null, skySid: 4081, mainGuide: true, sportsGuide: true, aliases: ["sky sports cricket hd", "ss cricket", "skysportscricket.uk"] },
  { id: "uk.sky.sports.golf", name: "Sky Sports Golf", category: "Sports", number: 305, freeviewSid: null, skySid: 4026, mainGuide: true, sportsGuide: true, aliases: ["sky sports golf hd", "ss golf", "skysportsgolf.uk"] },
  { id: "uk.sky.sports.f1", name: "Sky Sports F1", category: "Sports", number: 306, freeviewSid: null, skySid: 3835, mainGuide: true, sportsGuide: true, aliases: ["sky sports f1 hd", "sky sports formula 1", "ss f1", "skysportsf1.uk"] },
  { id: "uk.sky.sports.action", name: "Sky Sports Action", category: "Sports", number: 307, freeviewSid: null, skySid: 4022, mainGuide: true, sportsGuide: true, aliases: ["sky sports action hd", "sky sports nfl", "ss action", "skysportsaction.uk"] },
  { id: "uk.sky.sports.arena", name: "Sky Sports Arena", category: "Sports", number: 308, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["sky sports arena hd", "ss arena", "skysportsarena.uk"] },
  { id: "uk.sky.sports.tennis", name: "Sky Sports Tennis", category: "Sports", number: 309, freeviewSid: null, skySid: 1284, mainGuide: true, sportsGuide: true, aliases: ["sky sports tennis hd", "ss tennis", "skysportstennis.uk"] },
  { id: "uk.sky.sports.racing", name: "Sky Sports Racing", category: "Sports", number: 310, freeviewSid: null, skySid: 4032, mainGuide: true, sportsGuide: true, aliases: ["sky sports racing hd", "at the races", "atr", "skysportsracing.uk"] },
  { id: "uk.sky.sports.news", name: "Sky Sports News", category: "Sports", number: 311, freeviewSid: null, skySid: 4049, mainGuide: true, sportsGuide: true, aliases: ["sky sports news hd", "ssn", "ss news", "skysportsnews.uk"] },
  { id: "uk.sky.sports.mix", name: "Sky Sports Mix", category: "Sports", number: 312, freeviewSid: null, skySid: 4090, mainGuide: true, sportsGuide: true, aliases: ["sky sports mix hd", "ss mix", "skysportsmix.uk"] },
  { id: "uk.sky.sports.plus", name: "Sky Sports+", category: "Sports", number: 313, freeviewSid: null, skySid: 3940, mainGuide: true, sportsGuide: true, aliases: ["sky sports plus", "sky sports +", "sky sports+ main", "skysportsplus.uk"] },
  { id: "uk.tnt.sports.1", name: "TNT Sports 1", category: "Sports", number: 314, freeviewSid: null, skySid: 3625, mainGuide: true, sportsGuide: true, aliases: ["tnt sport 1", "tnt sports 1 hd", "bt sport 1", "bt sports 1", "bt sport 1 hd", "tntsports1.uk", "btsport1.uk"] },
  { id: "uk.tnt.sports.2", name: "TNT Sports 2", category: "Sports", number: 315, freeviewSid: null, skySid: 3627, mainGuide: true, sportsGuide: true, aliases: ["tnt sport 2", "tnt sports 2 hd", "bt sport 2", "bt sports 2", "bt sport 2 hd", "tntsports2.uk", "btsport2.uk"] },
  { id: "uk.tnt.sports.3", name: "TNT Sports 3", category: "Sports", number: 316, freeviewSid: null, skySid: 3629, mainGuide: true, sportsGuide: true, aliases: ["tnt sport 3", "tnt sports 3 hd", "bt sport 3", "bt sports 3", "bt sport 3 hd", "tntsports3.uk", "btsport3.uk"] },
  { id: "uk.tnt.sports.4", name: "TNT Sports 4", category: "Sports", number: 317, freeviewSid: null, skySid: 4040, mainGuide: true, sportsGuide: true, aliases: ["tnt sport 4", "tnt sports 4 hd", "bt sport 4", "bt sport espn", "bt sports 4", "tntsports4.uk", "btsport4.uk"] },
  { id: "uk.tnt.sports.ultimate", name: "TNT Sports Ultimate", category: "Sports", number: 318, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["tnt sport ultimate", "tnt sports ultimate 4k", "bt sport ultimate", "bt sport 4k", "tntsportsultimate.uk"] },
  { id: "uk.premier.sports.1", name: "Premier Sports 1", category: "Sports", number: 319, freeviewSid: null, skySid: 1289, mainGuide: true, sportsGuide: true, aliases: ["premier sport 1", "premier sports 1 hd", "viaplay sports 1", "viaplay 1", "premiersports1.uk"] },
  { id: "uk.premier.sports.2", name: "Premier Sports 2", category: "Sports", number: 320, freeviewSid: null, skySid: 1290, mainGuide: true, sportsGuide: true, aliases: ["premier sport 2", "premier sports 2 hd", "viaplay sports 2", "viaplay 2", "premiersports2.uk"] },
  { id: "uk.premier.sports.rugby", name: "Premier Sports Rugby", category: "Sports", number: 321, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["viaplay xtra", "premier sports xtra", "freesports", "premiersportsrugby.uk"] },
  { id: "uk.eurosport.1", name: "Eurosport 1", category: "Sports", number: 322, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["eurosport 1 hd", "british eurosport", "british eurosport 1", "eurosport 1 uk", "eurosport1.uk"] },
  { id: "uk.eurosport.2", name: "Eurosport 2", category: "Sports", number: 323, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["eurosport 2 hd", "british eurosport 2", "eurosport 2 uk", "eurosport2.uk"] },
  { id: "uk.racing.tv", name: "Racing TV", category: "Sports", number: 324, freeviewSid: null, skySid: 5272, mainGuide: true, sportsGuide: true, aliases: ["racing uk", "racingtv.uk"] },
  { id: "uk.mutv", name: "MUTV", category: "Sports", number: 325, freeviewSid: null, skySid: 1003, mainGuide: true, sportsGuide: true, aliases: ["mutv hd", "manchester united tv", "mutv.uk"] },
  { id: "uk.lfctv", name: "LFCTV", category: "Sports", number: 326, freeviewSid: null, skySid: 3845, mainGuide: true, sportsGuide: true, aliases: ["lfc tv", "liverpool tv", "lfctv hd", "lfctv.uk"] },
  { id: "uk.dazn.uk", name: "DAZN UK", category: "Sports", number: 327, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["dazn 1 uk", "dazn", "dazn.uk"] },
  { id: "uk.sky.sports.box.office", name: "Sky Sports Box Office", category: "Sports", number: 328, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["ss box office", "skysportsboxoffice.uk"] },
  { id: "uk.tnt.sports.box.office", name: "TNT Sports Box Office", category: "Sports", number: 329, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["bt sport box office", "tntsportsboxoffice.uk"] },
  { id: "uk.la.liga.tv", name: "LaLigaTV", category: "Sports", number: 332, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["la liga tv", "laligatv.uk"] },

  // Sky Cinema & Movies
  { id: "uk.sky.cinema.premiere", name: "Sky Cinema Premiere", category: "Movies", number: 401, freeviewSid: null, skySid: 4021, mainGuide: true, sportsGuide: false, aliases: ["sky premiere", "sky movies premiere", "sky cinema premiere hd", "skycinemapremiere.uk"] },
  { id: "uk.sky.cinema.hits", name: "Sky Cinema Hits", category: "Movies", number: 402, freeviewSid: null, skySid: 4033, mainGuide: true, sportsGuide: false, aliases: ["sky hits", "sky movies hits", "sky cinema hits hd", "skycinemahits.uk"] },
  { id: "uk.sky.cinema.greats", name: "Sky Cinema Greats", category: "Movies", number: 403, freeviewSid: null, skySid: 4015, mainGuide: true, sportsGuide: false, aliases: ["sky greats", "sky movies greats", "sky cinema greats hd", "skycinemagreats.uk"] },
  { id: "uk.sky.cinema.action", name: "Sky Cinema Action", category: "Movies", number: 404, freeviewSid: null, skySid: 4014, mainGuide: true, sportsGuide: false, aliases: ["sky action", "sky movies action", "sky cinema action & adventure", "sky cinema action hd", "skycinemaaction.uk"] },
  { id: "uk.sky.cinema.comedy", name: "Sky Cinema Comedy", category: "Movies", number: 405, freeviewSid: null, skySid: 4019, mainGuide: true, sportsGuide: false, aliases: ["sky comedy cinema", "sky movies comedy", "sky cinema comedy hd", "skycinemacomedy.uk"] },
  { id: "uk.sky.cinema.family", name: "Sky Cinema Family", category: "Movies", number: 406, freeviewSid: null, skySid: 4018, mainGuide: true, sportsGuide: false, aliases: ["sky family", "sky movies family", "skycinemafamily.uk"] },
  { id: "uk.sky.cinema.thriller", name: "Sky Cinema Thriller", category: "Movies", number: 407, freeviewSid: null, skySid: 4062, mainGuide: true, sportsGuide: false, aliases: ["sky thriller", "sky movies thriller", "sky cinema crime & thriller", "skycinemathriller.uk"] },
  { id: "uk.sky.cinema.drama", name: "Sky Cinema Drama", category: "Movies", number: 408, freeviewSid: null, skySid: 4016, mainGuide: true, sportsGuide: false, aliases: ["sky drama", "sky movies drama", "sky cinema drama & romance", "skycinemadrama.uk"] },
  { id: "uk.sky.cinema.scifi", name: "Sky Cinema Sci-Fi & Horror", category: "Movies", number: 409, freeviewSid: null, skySid: 4017, mainGuide: true, sportsGuide: false, aliases: ["sky sci-fi & horror", "sky cinema sci-fi", "sky cinema scifi", "sky movies sci-fi & horror", "skycinemascifi.uk"] },
  { id: "uk.sky.cinema.animation", name: "Sky Cinema Animation", category: "Movies", number: 410, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["sky movies animation", "skycinemaanimation.uk"] },
  { id: "uk.sky.cinema.select", name: "Sky Cinema Select", category: "Movies", number: 411, freeviewSid: null, skySid: 4020, mainGuide: true, sportsGuide: false, aliases: ["sky select", "sky movies select", "skycinemaselect.uk"] },

  // News
  { id: "uk.bbc.news", name: "BBC News", category: "News", number: 501, freeviewSid: 4415, skySid: 2085, mainGuide: true, sportsGuide: false, aliases: ["bbc news 24", "bbc news channel", "bbc news hd", "bbcnews.uk"] },
  { id: "uk.bbc.parliament", name: "BBC Parliament", category: "News", number: 502, freeviewSid: 4608, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["bbcparliament.uk"] },
  { id: "uk.sky.news", name: "Sky News", category: "News", number: 503, freeviewSid: 22080, skySid: 4050, mainGuide: true, sportsGuide: false, aliases: ["sky news uk", "sky news hd", "skynews.uk"] },
  { id: "uk.gb.news", name: "GB News", category: "News", number: 504, freeviewSid: 27840, skySid: 1196, mainGuide: true, sportsGuide: false, aliases: ["gbnews", "gb news hd", "gbnews.uk"] },
  { id: "uk.talktv", name: "TalkTV", category: "News", number: 505, freeviewSid: 27888, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["talk", "talk tv", "talktv.uk"] },
  { id: "uk.cnn.uk", name: "CNN International", category: "News", number: 506, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["cnn uk", "cnn intl", "cnn.uk", "cnninternational.uk"] },
  { id: "uk.bloomberg.uk", name: "Bloomberg TV", category: "News", number: 507, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["bloomberg", "bloomberg uk", "bloomberg television", "bloomberg.uk"] },
  { id: "uk.cnbc.uk", name: "CNBC", category: "News", number: 508, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["cnbc uk", "cnbc europe", "cnbc.uk"] },
  { id: "uk.al.jazeera.en", name: "Al Jazeera English", category: "News", number: 509, freeviewSid: 27072, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["al jazeera", "al jazeera eng", "aljazeeraenglish.uk"] },

  // Documentary
  { id: "uk.discovery", name: "Discovery Channel", category: "Documentary", number: 601, freeviewSid: null, skySid: 4003, mainGuide: true, sportsGuide: false, aliases: ["discovery", "discovery uk", "discovery hd", "discovery.uk"] },
  { id: "uk.discovery.history", name: "Discovery History", category: "Documentary", number: 602, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["discoveryhistory.uk"] },
  { id: "uk.discovery.science", name: "Discovery Science", category: "Documentary", number: 603, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["discoveryscience.uk"] },
  { id: "uk.discovery.turbo", name: "Discovery Turbo", category: "Documentary", number: 604, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["discoveryturbo.uk"] },
  { id: "uk.natgeo", name: "National Geographic", category: "Documentary", number: 605, freeviewSid: null, skySid: 4031, mainGuide: true, sportsGuide: false, aliases: ["nat geo", "natgeo", "national geographic uk", "national geographic hd", "natgeo.uk"] },
  { id: "uk.natgeo.wild", name: "National Geographic Wild", category: "Documentary", number: 606, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["nat geo wild", "natgeo wild", "natgeowild.uk"] },
  { id: "uk.sky.documentaries", name: "Sky Documentaries", category: "Documentary", number: 607, freeviewSid: null, skySid: 1136, mainGuide: true, sportsGuide: false, aliases: ["sky docs", "skydocumentaries.uk"] },
  { id: "uk.sky.nature", name: "Sky Nature", category: "Documentary", number: 608, freeviewSid: null, skySid: 1165, mainGuide: true, sportsGuide: false, aliases: ["skynature.uk"] },
  { id: "uk.sky.history", name: "Sky History", category: "Documentary", number: 609, freeviewSid: null, skySid: 4086, mainGuide: true, sportsGuide: false, aliases: ["history", "history uk", "history channel", "skyhistory.uk"] },
  { id: "uk.sky.history.2", name: "Sky History 2", category: "Documentary", number: 610, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["sky history2", "history 2", "skyhistory2.uk"] },
  { id: "uk.animal.planet", name: "Animal Planet", category: "Documentary", number: 611, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["animal planet uk", "animalplanet.uk"] },
  { id: "uk.pbs.america", name: "PBS America", category: "Documentary", number: 612, freeviewSid: 27744, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["pbsamerica.uk"] },
  { id: "uk.smithsonian", name: "Smithsonian Channel", category: "Documentary", number: 613, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["smithsonian", "smithsonian uk", "smithsonian.uk"] },

  // Kids
  { id: "uk.cbeebies", name: "CBeebies", category: "Kids", number: 701, freeviewSid: 4671, skySid: 2088, mainGuide: true, sportsGuide: false, aliases: ["cbeebies hd", "cbeebies.uk"] },
  { id: "uk.cbbc", name: "CBBC", category: "Kids", number: 702, freeviewSid: 4479, skySid: 2078, mainGuide: true, sportsGuide: false, aliases: ["cbbc hd", "cbbc.uk"] },
  { id: "uk.nickelodeon", name: "Nickelodeon", category: "Kids", number: 704, freeviewSid: null, skySid: 4069, mainGuide: true, sportsGuide: false, aliases: ["nick", "nickelodeon uk", "nickelodeon.uk"] },
  { id: "uk.nick.jr", name: "Nick Jr.", category: "Kids", number: 705, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["nick jr", "nick junior", "nickjr.uk"] },
  { id: "uk.cartoon.network", name: "Cartoon Network", category: "Kids", number: 707, freeviewSid: null, skySid: 4077, mainGuide: true, sportsGuide: false, aliases: ["cartoon network uk", "cartoonnetwork.uk"] },
  { id: "uk.boomerang", name: "Boomerang", category: "Kids", number: 708, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["boomerang uk", "boomerang.uk"] },
  { id: "uk.pop", name: "POP", category: "Kids", number: 710, freeviewSid: 27552, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["pop tv", "pop.uk"] },
  { id: "uk.tiny.pop", name: "Tiny Pop", category: "Kids", number: 711, freeviewSid: 27680, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["tinypop.uk"] },
  { id: "uk.sky.kids", name: "Sky Kids", category: "Kids", number: 712, freeviewSid: null, skySid: 1147, mainGuide: true, sportsGuide: false, aliases: ["skykids.uk"] },

  // Ireland
  { id: "ie.rte.one", name: "RTÉ One", category: "Entertainment", number: 901, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["rte one", "rte 1", "rte1", "rte1.ie", "rteone.ie"] },
  { id: "ie.rte.two", name: "RTÉ2", category: "Entertainment", number: 902, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["rte 2", "rte two", "rte2", "rte2.ie", "rtetwo.ie"] },
  { id: "ie.virgin.media.one", name: "Virgin Media One", category: "Entertainment", number: 903, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["virgin media 1", "tv3 ireland", "tv3 ie", "virginmediaone.ie"] },
  { id: "ie.virgin.media.two", name: "Virgin Media Two", category: "Entertainment", number: 904, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["virgin media 2", "3e", "virginmediatwo.ie"] },
  { id: "ie.virgin.media.three", name: "Virgin Media Three", category: "Entertainment", number: 905, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["virgin media 3", "be3", "virginmediathree.ie"] },
  { id: "ie.tg4", name: "TG4", category: "Entertainment", number: 906, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["tg4.ie"] },
  { id: "ie.rte.news", name: "RTÉ News", category: "News", number: 907, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["rte news", "rtenews.ie"] },
  { id: "ie.virgin.media.four", name: "Virgin Media Four", category: "Entertainment", number: 908, freeviewSid: null, skySid: null, mainGuide: false, sportsGuide: false, aliases: ["virgin media 4", "virginmediafour.ie"] },
];

/** Canonical UK and Irish channel definitions keyed by canonical channel ID. */
export const UK_CHANNELS = Object.fromEntries(CHANNEL_DEFS.map((ch) => [ch.id, ch]));

function normText(raw) {
  return String(raw || "")
    .normalize("NFKD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/\+/g, " plus ")
    .replace(/&/g, " and ")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function compactText(raw) {
  return normText(raw).replace(/\s+/g, "");
}

// Strip simulcast/region noise without stripping "+1" (timeshift schedules differ by an hour).
function stripChannelNoise(raw) {
  const cleaned = String(raw || "")
    .normalize("NFKD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/^(?:uk|gb|ie)\s*[:|\-]\s*/i, "")
    .replace(/\.(?:uk|ie)$/i, "")
    .trim();
  if (/\+\s*1\b/.test(cleaned)) return "";
  return cleaned
    .replace(/\((?:hd|sd|uhd|4k|fhd|uk|gb|london|england|wales|scotland|ni)\)/gi, " ")
    .replace(/\b(?:uhd|fhd|hd|sd|4k|uk|gb|london|england|south east|south west|north west|north east|midlands|yorkshire|granada|meridian|anglia|central|westcountry|tyne tees|border)\b/gi, " ")
    .replace(/\+/g, " plus ")
    .replace(/&/g, " and ")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function buildChannelIndexes() {
  const bySid = new Map();
  const byExact = new Map();
  const byCompact = new Map();
  const byStripped = new Map();

  for (const ch of CHANNEL_DEFS) {
    if (ch.freeviewSid != null) bySid.set(String(ch.freeviewSid), ch);
    const terms = [ch.id, ch.name, ...(ch.aliases || [])];
    for (const term of terms) {
      if (/^\d+$/.test(String(term))) {
        if (!bySid.has(String(term))) bySid.set(String(term), ch);
        continue;
      }
      const n = normText(term);
      const c = compactText(term);
      if (n && !byExact.has(n)) byExact.set(n, ch);
      if (c && !byCompact.has(c)) byCompact.set(c, ch);
      const s = stripChannelNoise(term);
      const sc = s.replace(/\s+/g, "");
      if (s && !byStripped.has(s)) byStripped.set(s, ch);
      if (sc && !byStripped.has(sc)) byStripped.set(sc, ch);
    }
  }
  return { bySid, byExact, byCompact, byStripped };
}

const CHANNEL_INDEX = buildChannelIndexes();

/** Resolve a channel ID, Freeview service_id, or display name to a canonical UK channel entry, or null. */
export function resolveUkChannel(...hints) {
  for (const hint of hints) {
    if (hint == null) continue;
    const raw = String(hint).trim();
    if (!raw) continue;
    if (UK_CHANNELS[raw]) return UK_CHANNELS[raw];
    if (/^\d+$/.test(raw) && CHANNEL_INDEX.bySid.has(raw)) return CHANNEL_INDEX.bySid.get(raw);
    const n = normText(raw);
    if (n && CHANNEL_INDEX.byExact.has(n)) return CHANNEL_INDEX.byExact.get(n);
    const c = compactText(raw);
    if (c && CHANNEL_INDEX.byCompact.has(c)) return CHANNEL_INDEX.byCompact.get(c);
  }
  for (const hint of hints) {
    if (hint == null) continue;
    const s = stripChannelNoise(hint);
    if (!s) continue;
    if (CHANNEL_INDEX.byStripped.has(s)) return CHANNEL_INDEX.byStripped.get(s);
    const sc = s.replace(/\s+/g, "");
    if (sc && CHANNEL_INDEX.byStripped.has(sc)) return CHANNEL_INDEX.byStripped.get(sc);
  }
  return null;
}

const SPORT_RULES = [
  ["Formula 1", /\b(formula\s*1|formula\s+one|f1\b|grand\s+prix\b.*\b(qualifying|practice|sprint|race|f1)|british\s+gp|monaco\s+gp|silverstone\s+gp)\b/i],
  ["UFC/MMA", /\b(ufc|mma|mixed\s+martial\s+arts|bellator|cage\s+warriors|pfl)\b/i],
  ["Boxing", /\b(boxing|heavyweight|welterweight|middleweight|featherweight|lightweight|bantamweight|cruiserweight|super[\s-]?middleweight|matchroom|queensberry|world\s+title\s+fight)\b/i],
  ["Rugby", /\b(rugby|six\s+nations|gallagher\s+premiership|premiership\s+rugby|united\s+rugby\s+championship|betfred\s+super\s+league|investec\s+champions\s+cup|british\s+(?:&|and)\s+irish\s+lions|nrl)\b/i],
  ["Cricket", /\b(cricket|the\s+ashes|test\s+match\b.*\b(cricket|england|australia|india|lords|oval|edgbaston|headingley|trent\s+bridge)|one[\s-]?day\s+international|odi\b|t20\b|twenty20|the\s+hundred\b.*\b(cricket|men's|women's)|ipl\b|county\s+championship|big\s+bash)\b/i],
  ["Tennis", /\b(tennis|wimbledon|roland[\s-]?garros|atp\s+tour|wta\s+tour|davis\s+cup|billie\s+jean\s+king\s+cup|laver\s+cup)\b/i],
  ["Golf", /\b(golf|ryder\s+cup|solheim\s+cup|pga\s+tour|pga\s+championship|dp\s+world\s+tour|liv\s+golf|the\s+masters\b.*\b(golf|augusta)|the\s+open\b.*\b(golf|championship))\b/i],
  ["Darts", /\b(darts|pdc\b|premier\s+league\s+darts|world\s+darts|grand\s+slam\s+of\s+darts|world\s+matchplay\b.*\bdarts|ally\s+pally)\b/i],
  ["Snooker", /\b(snooker|world\s+snooker|crucible\b.*\b(snooker|championship)|uk\s+championship\b.*\bsnooker|the\s+masters\b.*\bsnooker)\b/i],
];

const FOOTBALL_RE = /\b(football|premier\s+league|champions\s+league|europa\s+league|conference\s+league|fa\s+cup|carabao\s+cup|efl\s+cup|efl\b|scottish\s+premiership|la\s+liga|bundesliga|serie\s+a|ligue\s+1|women's\s+super\s+league|wsl\b|uefa|fifa|match\s+of\s+the\s+day|soccer\s+saturday|monday\s+night\s+football|super\s+sunday\b.*\b(premier|football|arsenal|liverpool|chelsea|manchester|tottenham|newcastle|aston\s+villa|everton))\b/i;
const AMERICAN_FOOTBALL_RE = /\b(nfl|american\s+football|super\s+bowl)\b/i;
const SOCCER_CONTEXT_RE = /\b(premier\s+league|champions\s+league|fa\s+cup|uefa|fifa|soccer|efl|scottish\s+premiership)\b/i;

/** Detect a canonical UK sport tag from programme metadata, or null if not one of the supported sports. */
export function detectSportTag(title, category, description) {
  // The description only counts for a programme already listed as sport: a hospital documentary whose blurb
  // mentions a rugby injury isn't live sport ("24 Hours in A&E" was tagged that way)
  const sporty = /\bsports?\b/i.test(String(category || ""));
  const text = [title, category, sporty ? description : null].filter(Boolean).join(" ");
  if (!text) return null;
  // Check specific sports before Football so "Premier League Darts" maps to Darts rather than Football.
  for (const [tag, re] of SPORT_RULES) {
    if (re.test(text)) return tag;
  }
  if (AMERICAN_FOOTBALL_RE.test(text) && !SOCCER_CONTEXT_RE.test(text)) return null;
  if (FOOTBALL_RE.test(text)) return "Football";
  return null;
}

function isSportProgramme(sportTag, category) {
  return Boolean(sportTag || /\bsports?\b/i.test(String(category || "")));
}

function inRollingWindow(startMs, endMs, nowMs) {
  if (!Number.isFinite(nowMs)) return true;
  return endMs >= nowMs - PAST_INGEST_WINDOW_MS && startMs <= nowMs + FUTURE_INGEST_WINDOW_MS;
}

function decodeXmlEntities(text) {
  return String(text || "").replace(/&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|apos);/g, (m, code) => {
    switch (code) {
      case "amp": return "&";
      case "lt": return "<";
      case "gt": return ">";
      case "quot": return '"';
      case "apos": return "'";
      default: {
        const cp = code.startsWith("#x") || code.startsWith("#X")
          ? parseInt(code.slice(2), 16)
          : parseInt(code.slice(1), 10);
        if (!Number.isFinite(cp) || cp <= 0 || cp > 0x10ffff) return m;
        return String.fromCodePoint(cp);
      }
    }
  });
}

function cleanXmlText(raw) {
  if (!raw) return "";
  let out = "";
  let pos = 0;
  while (pos < raw.length) {
    const cdataStart = raw.indexOf("<![CDATA[", pos);
    if (cdataStart < 0) {
      out += decodeXmlEntities(raw.slice(pos).replace(/<[^>]*>/g, ""));
      break;
    }
    if (cdataStart > pos) {
      out += decodeXmlEntities(raw.slice(pos, cdataStart).replace(/<[^>]*>/g, ""));
    }
    const cdataEnd = raw.indexOf("]]>", cdataStart + 9);
    if (cdataEnd < 0) {
      out += raw.slice(cdataStart + 9);
      break;
    }
    out += raw.slice(cdataStart + 9, cdataEnd);
    pos = cdataEnd + 3;
  }
  return out.replace(/\s+/g, " ").trim();
}

function parseXmlAttrs(openTag) {
  const attrs = {};
  const re = /([A-Za-z_][\w:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;
  let m;
  while ((m = re.exec(openTag))) {
    attrs[m[1]] = decodeXmlEntities(m[2] ?? m[3] ?? "");
  }
  return attrs;
}

function extractTagTexts(body, tagName) {
  const out = [];
  const openPrefix = `<${tagName}`;
  const closeTag = `</${tagName}>`;
  let pos = 0;
  while (pos < body.length) {
    const start = body.indexOf(openPrefix, pos);
    if (start < 0) break;
    const after = body.charCodeAt(start + openPrefix.length);
    if (after !== 62 && after !== 32 && after !== 9 && after !== 10 && after !== 13 && after !== 47) {
      pos = start + openPrefix.length;
      continue;
    }
    const gt = body.indexOf(">", start);
    if (gt < 0) break;
    if (body.charCodeAt(gt - 1) === 47) {
      pos = gt + 1;
      continue;
    }
    const end = body.indexOf(closeTag, gt + 1);
    if (end < 0) break;
    const text = cleanXmlText(body.slice(gt + 1, end));
    if (text) out.push(text);
    pos = end + closeTag.length;
  }
  return out;
}

function extractIconSrc(body) {
  const idx = body.indexOf("<icon");
  if (idx < 0) return null;
  const gt = body.indexOf(">", idx);
  if (gt < 0) return null;
  const attrs = parseXmlAttrs(body.slice(idx, gt + 1));
  return attrs.src ? attrs.src.trim() : null;
}

/** Parse XMLTV timestamps ("YYYYMMDDHHmmss ±HHMM", "YYYYMMDDHHmm ±HHMM", or ISO-8601) into epoch ms. */
function parseXmltvTime(raw) {
  if (!raw) return null;
  const s = String(raw).trim();
  const m = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?(?:\s*(Z|([+-])(\d{2}):?(\d{2})))?$/i.exec(s);
  if (m) {
    const year = Number(m[1]);
    const month = Number(m[2]);
    const day = Number(m[3]);
    const hour = Number(m[4]);
    const min = Number(m[5]);
    const sec = m[6] ? Number(m[6]) : 0;
    if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || min > 59 || sec > 59) return null;
    let offsetMs = 0;
    if (m[8]) {
      const sign = m[8] === "-" ? -1 : 1;
      offsetMs = sign * (Number(m[9]) * 60 + Number(m[10])) * 60 * 1000;
    }
    return Date.UTC(year, month - 1, day, hour, min, sec) - offsetMs;
  }
  const iso = Date.parse(s.replace(/([+-]\d{2})(\d{2})$/, "$1:$2"));
  return Number.isFinite(iso) ? iso : null;
}

function* scanElements(xml, tagName) {
  const openPrefix = `<${tagName}`;
  const closeTag = `</${tagName}>`;
  let pos = 0;
  while (pos < xml.length) {
    const start = xml.indexOf(openPrefix, pos);
    if (start < 0) break;
    const nextChar = xml.charCodeAt(start + openPrefix.length);
    if (nextChar !== 62 && nextChar !== 32 && nextChar !== 9 && nextChar !== 10 && nextChar !== 13 && nextChar !== 47) {
      pos = start + openPrefix.length;
      continue;
    }
    const gt = xml.indexOf(">", start);
    if (gt < 0) break;
    const openTag = xml.slice(start, gt + 1);
    if (xml.charCodeAt(gt - 1) === 47) {
      yield { attrs: parseXmlAttrs(openTag), body: "" };
      pos = gt + 1;
      continue;
    }
    const end = xml.indexOf(closeTag, gt + 1);
    if (end < 0) break;
    yield { attrs: parseXmlAttrs(openTag), body: xml.slice(gt + 1, end) };
    pos = end + closeTag.length;
  }
}

function mergeChannel(channelsMap, canonical, logo, sourceId) {
  const prev = channelsMap.get(canonical.id);
  const def = UK_CHANNELS[canonical.id] || canonical;
  if (!prev) {
    channelsMap.set(canonical.id, {
      id: canonical.id,
      name: canonical.name,
      category: canonical.category,
      number: canonical.number,
      logo: logo || null,
      sourceId: sourceId != null ? String(sourceId) : null,
      mainGuide: Boolean(def.mainGuide),
      sportsGuide: Boolean(def.sportsGuide),
    });
  } else {
    if (!prev.logo && logo) prev.logo = logo;
    if (!prev.sourceId && sourceId != null) prev.sourceId = String(sourceId);
  }
}

function mergeProgramme(programmesMap, prog) {
  const key = `${prog.channelId}:${prog.startMs}`;
  const prev = programmesMap.get(key);
  if (!prev || (!prev.description && prog.description) || (!prev.icon && prog.icon)) {
    programmesMap.set(key, prog);
  }
}

/** Pure XMLTV parser mapping <channel> and <programme> tags onto canonical UK channels. */
export function parseXmltv(xmlText, nowMs = null) {
  const xml = typeof xmlText === "string" ? xmlText : "";
  const channelsMap = new Map();
  const programmesMap = new Map();
  const xmltvToCanonical = new Map();

  for (const el of scanElements(xml, "channel")) {
    const rawId = el.attrs.id ? el.attrs.id.trim() : "";
    if (!rawId) continue;
    const displayNames = extractTagTexts(el.body, "display-name");
    const canonical = resolveUkChannel(rawId, ...displayNames);
    if (!canonical) continue;
    xmltvToCanonical.set(rawId, canonical);
    mergeChannel(channelsMap, canonical, extractIconSrc(el.body), rawId);
  }

  for (const el of scanElements(xml, "programme")) {
    const rawChan = el.attrs.channel ? el.attrs.channel.trim() : "";
    if (!rawChan) continue;
    const canonical = xmltvToCanonical.get(rawChan) || resolveUkChannel(rawChan);
    if (!canonical) continue;
    if (!channelsMap.has(canonical.id)) mergeChannel(channelsMap, canonical, null, rawChan);

    const startMs = parseXmltvTime(el.attrs.start);
    const endMs = parseXmltvTime(el.attrs.stop);
    if (startMs == null || endMs == null || endMs <= startMs) continue;
    if (!inRollingWindow(startMs, endMs, nowMs)) continue;

    const title = extractTagTexts(el.body, "title")[0];
    if (!title) continue;
    const subtitle = extractTagTexts(el.body, "sub-title")[0] || null;
    const description = extractTagTexts(el.body, "desc")[0] || null;
    const categories = extractTagTexts(el.body, "category");
    const category = categories.length ? categories.join(", ") : null;
    const icon = extractIconSrc(el.body);
    const sportTag = detectSportTag(title, category, description);
    mergeProgramme(programmesMap, {
      channelId: canonical.id,
      startMs,
      endMs,
      title,
      subtitle,
      description,
      category,
      icon,
      isSport: isSportProgramme(sportTag, category),
      sportTag,
    });
  }

  return {
    channels: [...channelsMap.values()],
    programmes: [...programmesMap.values()],
  };
}

function parseFreeviewTime(val) {
  if (typeof val === "number" && Number.isFinite(val)) {
    return val < 1e11 ? Math.round(val * 1000) : Math.round(val);
  }
  if (typeof val === "string") {
    const s = val.trim();
    if (!s) return null;
    if (/^\d+(\.\d+)?$/.test(s)) {
      const n = Number(s);
      return n < 1e11 ? Math.round(n * 1000) : Math.round(n);
    }
    const ms = Date.parse(s.replace(/([+-]\d{2})(\d{2})$/, "$1:$2"));
    return Number.isFinite(ms) ? ms : null;
  }
  return null;
}

function parseDurationMs(val) {
  if (typeof val === "number" && Number.isFinite(val) && val > 0) {
    return Math.round(val * 1000);
  }
  if (typeof val !== "string") return null;
  const s = val.trim();
  if (/^\d+$/.test(s)) return Number(s) * 1000;
  const m = /^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/i.exec(s);
  if (!m) return null;
  const sec = (Number(m[1] || 0) * 86400) + (Number(m[2] || 0) * 3600) + (Number(m[3] || 0) * 60) + Number(m[4] || 0);
  return sec > 0 ? sec * 1000 : null;
}

function normalizeImageUrl(val) {
  const raw = typeof val === "string" ? val : val && typeof val.url === "string" ? val.url : "";
  const s = raw.trim();
  if (!s) return null;
  if (s.startsWith("/")) return `https://www.freeview.co.uk${s}`;
  return /^https?:\/\//i.test(s) ? s : null;
}

/** Pure Freeview TV Guide JSON parser mapping service IDs and titles to canonical UK channels. */
export function parseFreeviewGuide(json, nowMs = null) {
  const root = json && json.data ? json.data : json;
  const rawChannels = Array.isArray(root)
    ? root
    : Array.isArray(root && root.programs) ? root.programs
      : Array.isArray(root && root.channels) ? root.channels
        : [];

  const channelsMap = new Map();
  const programmesMap = new Map();

  for (const ch of rawChannels) {
    if (!ch || typeof ch !== "object") continue;
    const sid = ch.service_id ?? ch.serviceId ?? ch.sid ?? ch.id;
    const titleHint = ch.title || ch.name || ch.channel_name || ch.channelName;
    const canonical = resolveUkChannel(sid, titleHint);
    if (!canonical) continue;
    const logo = normalizeImageUrl(ch.logo || ch.image || ch.icon);
    mergeChannel(channelsMap, canonical, logo, sid ?? titleHint);

    const events = Array.isArray(ch.events) ? ch.events
      : Array.isArray(ch.programmes) ? ch.programmes
        : Array.isArray(ch.programs) ? ch.programs
          : [];

    for (const ev of events) {
      if (!ev || typeof ev !== "object") continue;
      const title = String(ev.main_title || ev.title || ev.name || "").trim();
      if (!title) continue;
      const startMs = parseFreeviewTime(ev.start_time ?? ev.startTime ?? ev.start ?? ev.startMs);
      if (startMs == null) continue;
      let endMs = parseFreeviewTime(ev.end_time ?? ev.endTime ?? ev.stop ?? ev.end ?? ev.endMs);
      if (endMs == null) {
        const durMs = ev.durationMs != null ? Number(ev.durationMs) : parseDurationMs(ev.duration);
        if (Number.isFinite(durMs) && durMs > 0) endMs = startMs + durMs;
      }
      if (endMs == null || endMs <= startMs) continue;
      if (!inRollingWindow(startMs, endMs, nowMs)) continue;

      const subtitle = String(ev.secondary_title || ev.subtitle || ev.episode_title || "").trim() || null;
      const description = String(ev.synopsis || ev.description || ev.short_synopsis || "").trim() || null;
      const category = String(
        ev.genre || ev.category || (Array.isArray(ev.genres) ? ev.genres.filter(Boolean).join(", ") : "") || ""
      ).trim() || null;
      const icon = normalizeImageUrl(ev.image || ev.image_url || ev.fallback_image_url || ev.icon);
      const sportTag = detectSportTag(title, category, description);
      mergeProgramme(programmesMap, {
        channelId: canonical.id,
        startMs,
        endMs,
        title,
        subtitle,
        description,
        category,
        icon,
        isSport: isSportProgramme(sportTag, category),
        sportTag,
      });
    }
  }

  return {
    channels: [...channelsMap.values()],
    programmes: [...programmesMap.values()],
  };
}

const SKY_SID_MAP = new Map(
  CHANNEL_DEFS.filter((ch) => ch.skySid != null).map((ch) => [String(ch.skySid), ch])
);

function lookupSidChannel(sidToChannel, sid) {
  if (!sidToChannel) return SKY_SID_MAP.get(sid) || null;
  if (sidToChannel instanceof Map) {
    return sidToChannel.get(sid) || sidToChannel.get(Number(sid)) || null;
  }
  return sidToChannel[sid] || null;
}

function formatSkySubtitle(ev) {
  const season = Number(ev.seasonnumber);
  const episode = Number(ev.episodenumber);
  const hasSeason = Number.isInteger(season) && season > 0;
  const hasEpisode = Number.isInteger(episode) && episode > 0;
  if (hasSeason && hasEpisode) return `S${season} E${episode}`;
  if (hasEpisode) return `Ep ${episode}`;
  if (hasSeason) return `Season ${season}`;
  return null;
}

/** Pure Sky UK linear schedule JSON parser mapping Sky SIDs onto canonical UK channels. */
export function parseSkySchedule(json, sidToChannel = SKY_SID_MAP, nowMs = null) {
  const scheduleList = Array.isArray(json && json.schedule)
    ? json.schedule
    : Array.isArray(json) ? json : [];

  const channelsMap = new Map();
  const programmesMap = new Map();

  for (const ch of scheduleList) {
    if (!ch || typeof ch !== "object") continue;
    const sid = String(ch.sid ?? ch.channelSid ?? ch.serviceId ?? "").trim();
    if (!sid) continue;
    const canonical = lookupSidChannel(sidToChannel, sid);
    if (!canonical) continue;

    const logo = `https://d2n0069hmnqmmx.cloudfront.net/epgdata/1.0/newchanlogos/150/150/skychb${sid}.png`;
    mergeChannel(channelsMap, canonical, logo, sid);

    const events = Array.isArray(ch.events) ? ch.events : [];
    for (const ev of events) {
      if (!ev || typeof ev !== "object") continue;
      const title = String(ev.t || ev.title || "").trim();
      if (!title) continue;

      const st = Number(ev.st);
      const dur = Number(ev.d);
      if (!Number.isFinite(st) || !Number.isFinite(dur) || dur <= 0) continue;

      const startMs = Math.round(st * 1000);
      const endMs = Math.round((st + dur) * 1000);
      if (endMs <= startMs || !inRollingWindow(startMs, endMs, nowMs)) continue;

      const description = typeof ev.sy === "string" && ev.sy.trim() ? ev.sy.trim() : null;
      const subtitle = formatSkySubtitle(ev);
      const category = typeof ev.genre === "string" && ev.genre.trim()
        ? ev.genre.trim()
        : (typeof ev.category === "string" && ev.category.trim() ? ev.category.trim() : null);
      const uuid = typeof ev.programmeuuid === "string" ? ev.programmeuuid.trim() : "";
      const icon = uuid ? `https://images.metadata.sky.com/pd-image/${uuid}/16-9/640` : null;
      const sportTag = detectSportTag(title, category, description);

      mergeProgramme(programmesMap, {
        channelId: canonical.id,
        startMs,
        endMs,
        title,
        subtitle,
        description,
        category,
        icon,
        isSport: isSportProgramme(sportTag, category),
        sportTag,
      });
    }
  }

  return {
    channels: [...channelsMap.values()],
    programmes: [...programmesMap.values()],
  };
}

/** Fetch 7-day UK TV schedules (-1d yesterday through +5d future) from Sky's public linear endpoint. */
export async function fetchSkySources(now = Date.now(), fetchImpl = globalThis.fetch) {
  const sids = [...SKY_SID_MAP.keys()];
  const sidChunks = [];
  for (let i = 0; i < sids.length; i += SKY_SID_CHUNK) {
    sidChunks.push(sids.slice(i, i + SKY_SID_CHUNK));
  }

  const base = new Date(now);
  const dates = [];
  for (let offset = -1; offset <= 5; offset++) {
    const d = new Date(Date.UTC(base.getUTCFullYear(), base.getUTCMonth(), base.getUTCDate() + offset));
    const y = d.getUTCFullYear();
    const m = String(d.getUTCMonth() + 1).padStart(2, "0");
    const day = String(d.getUTCDate()).padStart(2, "0");
    dates.push(`${y}${m}${day}`);
  }

  const requests = [];
  for (const dateStr of dates) {
    for (const chunk of sidChunks) {
      const url = `${SKY_SCHEDULE_BASE}/${dateStr}/${chunk.join(",")}`;
      requests.push(
        Promise.resolve()
          .then(() => fetchImpl(url, {
            headers: { "User-Agent": "Lumen/1.0", Accept: "application/json" },
            signal: AbortSignal.timeout(10000),
          }))
          .then(async (res) => (res && res.ok ? await res.json().catch(() => null) : null))
          .catch(() => null)
      );
    }
  }

  const payloads = await Promise.all(requests);
  const channelsMap = new Map();
  const programmesMap = new Map();
  let okCount = 0;

  for (const json of payloads) {
    if (!json) continue;
    okCount++;
    const parsed = parseSkySchedule(json, SKY_SID_MAP, now);
    for (const ch of parsed.channels) mergeChannel(channelsMap, ch, ch.logo, ch.sourceId);
    for (const pr of parsed.programmes) mergeProgramme(programmesMap, pr);
  }

  if (!okCount || !programmesMap.size) throw new Error("Sky EPG unavailable");
  return { channels: [...channelsMap.values()], programmes: [...programmesMap.values()] };
}

async function readMetaTimestamp(env, key) {
  const v = (await env.DB.prepare("SELECT v FROM lumos_meta WHERE k = ?").bind(key).all()).results?.[0]?.v;
  if (!v) return 0;
  const num = Number(v);
  if (Number.isFinite(num) && num > 0) return num;
  try {
    const parsed = JSON.parse(v);
    return Number(parsed && parsed.at) || 0;
  } catch (_) {
    return 0;
  }
}

async function decodeXmltvResponse(res, url) {
  const bytes = new Uint8Array(await res.arrayBuffer());
  const isGzipMagic = bytes.length >= 2 && bytes[0] === 0x1f && bytes[1] === 0x8b;
  const contentType = res.headers.get("Content-Type") || "";
  const cleanPath = String(url).split("?")[0].toLowerCase();
  const hintsGzip = cleanPath.endsWith(".gz") || /gzip/i.test(contentType);
  if ((isGzipMagic || (hintsGzip && bytes.length >= 2 && bytes[0] === 0x1f)) && typeof DecompressionStream !== "undefined") {
    const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"));
    return new Response(stream).text();
  }
  return new TextDecoder().decode(bytes);
}

async function fetchXmltvSource(url, now, fetchImpl) {
  const res = await fetchImpl(url, {
    headers: { "User-Agent": "Lumen/1.0" },
    signal: AbortSignal.timeout(15000),
  });
  if (!res || !res.ok) throw new Error(`XMLTV fetch ${res ? res.status : "failed"}`);
  const xml = await decodeXmltvResponse(res, url);
  return parseXmltv(xml, now);
}

/** Fetch today + tomorrow Freeview UK TV guide JSON as fallback when Sky EPG is unavailable. */
export async function fetchFreeviewSources(now, fetchImpl = globalThis.fetch) {
  const d = new Date(now);
  const todaySec = Math.floor(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate(), 0, 0, 0) / 1000);
  const starts = [todaySec, todaySec + 86400];
  const channelsMap = new Map();
  const programmesMap = new Map();
  let okCount = 0;

  for (const startSec of starts) {
    const res = await fetchImpl(`${FREEVIEW_BASE}&start=${startSec}`, {
      headers: { "User-Agent": "Lumen/1.0", Accept: "application/json" },
      signal: AbortSignal.timeout(10000),
    });
    if (!res || !res.ok) continue;
    const json = await res.json().catch(() => null);
    if (!json) continue;
    okCount++;
    const parsed = parseFreeviewGuide(json, now);
    for (const ch of parsed.channels) mergeChannel(channelsMap, ch, ch.logo, ch.sourceId);
    for (const pr of parsed.programmes) mergeProgramme(programmesMap, pr);
  }
  if (!okCount) throw new Error("Freeview guide unavailable");
  return { channels: [...channelsMap.values()], programmes: [...programmesMap.values()] };
}

async function batchWrite(env, statements) {
  for (let i = 0; i < statements.length; i += BATCH_CHUNK) {
    await env.DB.batch(statements.slice(i, i + BATCH_CHUNK));
  }
}

/** Build multi-row INSERT OR REPLACE statements (8 rows = 80 params per SQL) for D1 batch efficiency. */
export function buildProgrammeWriteStatements(db, programmes) {
  const writes = [];
  for (let i = 0; i < programmes.length; i += PROG_ROW_CHUNK) {
    const chunk = programmes.slice(i, i + PROG_ROW_CHUNK);
    const placeholders = chunk.map(() => "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").join(", ");
    const params = [];
    for (const p of chunk) {
      params.push(
        p.channelId,
        p.startMs,
        p.endMs,
        p.title,
        p.subtitle ?? null,
        p.description ?? null,
        p.category ?? null,
        p.icon ?? null,
        p.isSport ? 1 : 0,
        p.sportTag ?? null
      );
    }
    writes.push(
      db.prepare(
        `INSERT OR REPLACE INTO lumos_epg_programmes (channel_id, start_ms, end_ms, title, subtitle, description, category, icon, is_sport, sport_tag) VALUES ${placeholders}`
      ).bind(...params)
    );
  }
  return writes;
}

/**
 * Refresh UK EPG channels and programmes from optional XMLTV, Sky UK 7-day schedule, and Freeview fallback.
 * Skips when refreshed within the last 12 hours unless force === true.
 */
export async function refreshUkEpg(env, now = Date.now(), fetchImpl = globalThis.fetch, force = false) {
  const lastAt = await readMetaTimestamp(env, META_LAST_KEY);
  if (!force && lastAt > 0 && now - lastAt < REFRESH_TTL_MS) {
    return { skipped: true, updatedAt: lastAt, channels: 0, programmes: 0 };
  }

  const channelsMap = new Map();
  const programmesMap = new Map();
  const sources = [];
  const errors = [];

  const xmltvUrl = (typeof env.UK_EPG_XMLTV_URL === "string" && env.UK_EPG_XMLTV_URL.trim())
    || (typeof env.EPG_XMLTV_URL === "string" && env.EPG_XMLTV_URL.trim())
    || "";

  if (xmltvUrl) {
    try {
      const parsed = await fetchXmltvSource(xmltvUrl, now, fetchImpl);
      for (const ch of parsed.channels) mergeChannel(channelsMap, ch, ch.logo, ch.sourceId);
      for (const pr of parsed.programmes) mergeProgramme(programmesMap, pr);
      sources.push("xmltv");
    } catch (e) {
      errors.push(e.message);
    }
  }

  if (!programmesMap.size || env.UK_EPG_INCLUDE_SKY === "1") {
    try {
      const parsed = await fetchSkySources(now, fetchImpl);
      for (const ch of parsed.channels) mergeChannel(channelsMap, ch, ch.logo, ch.sourceId);
      for (const pr of parsed.programmes) mergeProgramme(programmesMap, pr);
      sources.push("sky");
    } catch (e) {
      errors.push(e.message);
    }
  }

  if (!programmesMap.size || env.UK_EPG_INCLUDE_FREEVIEW === "1") {
    try {
      const parsed = await fetchFreeviewSources(now, fetchImpl);
      for (const ch of parsed.channels) mergeChannel(channelsMap, ch, ch.logo, ch.sourceId);
      for (const pr of parsed.programmes) mergeProgramme(programmesMap, pr);
      sources.push("freeview");
    } catch (e) {
      errors.push(e.message);
    }
  }

  if (!sources.length) {
    return { skipped: false, ok: false, updatedAt: lastAt, channels: 0, programmes: 0, errors };
  }

  // D1's free plan allows 100k rows written a day, and every row written also rewrites its indexes. Rewriting the whole
  // week's guide twice a day used ~90% of that, so only new or changed rows are written; reading what is there is cheap.
  const oldChans = new Map(((await env.DB.prepare(
    "SELECT id, name, category, number, logo, source_id FROM lumos_epg_channels"
  ).all()).results || []).map((r) => [r.id, sig(r.name, r.category, r.number, r.logo, r.source_id)]));
  const chanStmt = env.DB.prepare(
    "INSERT OR REPLACE INTO lumos_epg_channels (id, name, category, number, logo, source_id, at) VALUES (?, ?, ?, ?, ?, ?, ?)"
  );
  const chanWrites = [...channelsMap.values()]
    .filter((c) => oldChans.get(c.id) !== sig(c.name, c.category, c.number, c.logo, c.sourceId))
    .map((c) => chanStmt.bind(c.id, c.name, c.category, c.number ?? null, c.logo ?? null, c.sourceId ?? null, now));

  const oldProgs = new Map(((await env.DB.prepare(
    "SELECT channel_id, start_ms, end_ms, title, subtitle, description, category, icon, is_sport, sport_tag FROM lumos_epg_programmes WHERE end_ms > ?"
  ).bind(now - RETENTION_MS).all()).results || []).map((r) => [
    `${r.channel_id}|${Number(r.start_ms)}`,
    sig(r.end_ms, r.title, r.subtitle, r.description, r.category, r.icon, r.is_sport ? 1 : 0, r.sport_tag),
  ]));
  const progs = [...programmesMap.values()];
  const changed = progs.filter((p) => oldProgs.get(`${p.channelId}|${p.startMs}`) !== sig(
    p.endMs, p.title, p.subtitle, p.description, p.category, p.icon, p.isSport ? 1 : 0, p.sportTag
  ));
  const progWrites = buildProgrammeWriteStatements(env.DB, changed);

  await batchWrite(env, chanWrites);
  await batchWrite(env, progWrites);
  await env.DB.prepare("INSERT OR REPLACE INTO lumos_meta (k, v) VALUES (?, ?)").bind(META_LAST_KEY, String(now)).run();

  return {
    skipped: false,
    ok: true,
    updatedAt: now,
    channels: chanWrites.length,
    programmes: progs.length,
    written: changed.length,
    sources,
  };
}

/** A row's comparable content: blanks and nulls alike, numbers as numbers. */
function sig(...values) {
  return JSON.stringify(values.map((x) => (x == null || x === "" ? null : typeof x === "bigint" ? Number(x) : x)));
}

/** Read UK EPG channels and programmes within a clamped catch-up/upcoming window. */
export async function readUkEpg(env, opts = {}, now = Date.now()) {
  const rawFrom = Number(opts && (opts.fromMs ?? opts.from));
  const rawTo = Number(opts && (opts.toMs ?? opts.to));
  const minBound = now - RETENTION_MS;
  const maxBound = now + RETENTION_MS;

  const fromMs = Math.floor(
    Number.isFinite(rawFrom)
      ? Math.max(minBound, Math.min(maxBound, rawFrom))
      : now - DEFAULT_PAST_MS
  );
  const defaultTo = fromMs + DEFAULT_PAST_MS + DEFAULT_FUTURE_MS;
  const maxTo = Math.min(maxBound, fromMs + MAX_READ_SPAN_MS);
  const toMs = Math.floor(
    Number.isFinite(rawTo)
      ? Math.max(fromMs, Math.min(maxTo, rawTo))
      : Math.max(fromMs, Math.min(maxTo, Number.isFinite(rawFrom) ? defaultTo : now + DEFAULT_FUTURE_MS))
  );

  const filterChannels = Array.isArray(opts && opts.channels)
    ? [...new Set(opts.channels.filter((id) => typeof id === "string" && id.trim()).map((id) => id.trim()))].slice(0, 200)
    : [];

  let chanRows = [];
  let progRows = [];

  if (filterChannels.length) {
    for (let i = 0; i < filterChannels.length; i += BATCH_CHUNK) {
      const chunk = filterChannels.slice(i, i + BATCH_CHUNK);
      const ph = chunk.map(() => "?").join(",");
      const cs = (await env.DB.prepare(
        `SELECT id, name, category, number, logo, source_id, at FROM lumos_epg_channels WHERE id IN (${ph})`
      ).bind(...chunk).all()).results || [];
      chanRows.push(...cs);

      const ps = (await env.DB.prepare(
        `SELECT channel_id, start_ms, end_ms, title, subtitle, description, category, icon, is_sport, sport_tag FROM lumos_epg_programmes WHERE end_ms > ? AND start_ms < ? AND channel_id IN (${ph}) ORDER BY start_ms ASC`
      ).bind(fromMs, toMs, ...chunk).all()).results || [];
      progRows.push(...ps);
    }
  } else {
    chanRows = (await env.DB.prepare(
      "SELECT id, name, category, number, logo, source_id, at FROM lumos_epg_channels ORDER BY COALESCE(number, 9999) ASC, name ASC"
    ).all()).results || [];
    progRows = (await env.DB.prepare(
      "SELECT channel_id, start_ms, end_ms, title, subtitle, description, category, icon, is_sport, sport_tag FROM lumos_epg_programmes WHERE end_ms > ? AND start_ms < ? ORDER BY channel_id ASC, start_ms ASC"
    ).bind(fromMs, toMs).all()).results || [];
  }

  chanRows.sort((a, b) => (a.number ?? 9999) - (b.number ?? 9999) || String(a.name).localeCompare(String(b.name)));
  progRows.sort((a, b) => String(a.channel_id).localeCompare(String(b.channel_id)) || a.start_ms - b.start_ms);

  const updatedAt = await readMetaTimestamp(env, META_LAST_KEY);
  return {
    updatedAt,
    fromMs,
    toMs,
    channels: chanRows.map((r) => {
      const def = UK_CHANNELS[r.id];
      return {
        id: r.id,
        name: r.name,
        category: r.category,
        number: r.number != null ? Number(r.number) : null,
        logo: r.logo || null,
        sourceId: r.source_id || null,
        mainGuide: def ? Boolean(def.mainGuide) : Boolean(r.main_guide),
        sportsGuide: def ? Boolean(def.sportsGuide) : Boolean(r.sports_guide),
      };
    }),
    programmes: progRows.map((r) => ({
      channelId: r.channel_id,
      startMs: Number(r.start_ms),
      endMs: Number(r.end_ms),
      title: r.title,
      subtitle: r.subtitle || null,
      description: r.description || null,
      category: r.category || null,
      icon: r.icon || null,
      isSport: Boolean(r.is_sport),
      sportTag: r.sport_tag || null,
    })),
  };
}

function cleanTitleForTmdb(raw) {
  return String(raw || "")
    .replace(/^(?:new|live|premiere|exclusive|repeat|film|movie)\s*:\s*/i, "")
    .replace(/\s*\((?:r|ad|sl|s\d+)\)\s*$/i, "")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 140);
}

/**
 * Enrich a programme with a TMDB 16:9 backdrop and en-GB description, cached 30 days in lumos_meta.
 * Safely degrades to null fields when LUMOS_TMDB_KEY is absent.
 */
export async function enrichEpgProgramme(env, opts = {}, now = Date.now()) {
  const empty = { backdrop: null, description: null, tmdbId: null, mediaType: null };
  const cleanedTitle = cleanTitleForTmdb(opts && opts.title);
  if (!cleanedTitle) return empty;

  const channelId = typeof (opts && opts.channelId) === "string" ? opts.channelId.trim() : "";
  const category = typeof (opts && opts.category) === "string" ? opts.category.trim() : "";
  const def = channelId ? UK_CHANNELS[channelId] : null;
  const isMovie = /movie|film|cinema/i.test(category) || (def && def.category === "Movies") || channelId.includes("cinema") || channelId.includes("film4");
  const mode = isMovie ? "movie" : "multi";
  const keySlug = normText(cleanedTitle);
  if (!keySlug) return empty;

  const cacheKey = `epg:enrich:${mode}:${keySlug}`;
  const rawCached = (await env.DB.prepare("SELECT v FROM lumos_meta WHERE k = ?").bind(cacheKey).all()).results?.[0]?.v;
  if (rawCached) {
    try {
      const parsed = JSON.parse(rawCached);
      if (parsed && Number.isFinite(parsed.at) && now - parsed.at < ENRICH_TTL_MS) {
        return {
          backdrop: parsed.backdrop || null,
          description: parsed.description || null,
          tmdbId: parsed.tmdbId ?? null,
          mediaType: parsed.mediaType || null,
        };
      }
    } catch (_) { /* refresh */ }
  }

  if (!env.LUMOS_TMDB_KEY) return empty;

  let data = isMovie
    ? await tmdbGet(env, `/search/movie?query=${encodeURIComponent(cleanedTitle)}&include_adult=false`, { language: "en-GB" })
    : await tmdbGet(env, `/search/multi?query=${encodeURIComponent(cleanedTitle)}&include_adult=false`, { language: "en-GB" });

  let results = Array.isArray(data && data.results) ? data.results : [];
  if (isMovie && !results.length) {
    data = await tmdbGet(env, `/search/multi?query=${encodeURIComponent(cleanedTitle)}&include_adult=false`, { language: "en-GB" });
    results = Array.isArray(data && data.results) ? data.results : [];
  }
  if (!data) return empty;

  const candidates = results.filter((r) => r && (r.media_type === "movie" || r.media_type === "tv" || !r.media_type));
  const hit = candidates.find((r) => Boolean(r.backdrop_path)) || candidates[0] || null;
  const result = {
    backdrop: hit && hit.backdrop_path ? `https://image.tmdb.org/t/p/w1280${hit.backdrop_path}` : null,
    description: hit && typeof hit.overview === "string" && hit.overview.trim() ? hit.overview.trim() : null,
    tmdbId: hit && Number.isFinite(hit.id) ? hit.id : null,
    mediaType: hit ? (hit.media_type || (isMovie ? "movie" : "tv")) : null,
  };

  await env.DB.prepare("INSERT OR REPLACE INTO lumos_meta (k, v) VALUES (?, ?)")
    .bind(cacheKey, JSON.stringify({ at: now, ...result }))
    .run();

  return result;
}

/** Delete expired programmes older than 7 days and expired phone IPTV setup codes. */
export async function pruneEpg(env, now = Date.now()) {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM lumos_epg_programmes WHERE end_ms < ?").bind(now - RETENTION_MS),
    env.DB.prepare("DELETE FROM lumos_iptv_setup WHERE expires < ?").bind(now),
  ]);
}
