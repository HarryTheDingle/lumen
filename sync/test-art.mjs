// The picture check's title matching (art.js)
//   node sync/test-art.mjs
import { sameTitle } from "./art.js";
const cases = [
  [{ name: "Band of Brothers", year: "2001" }, { name: "Band of Brothers", first_air_date: "2001-09-09" }, true],
  [{ name: "Band of Brothers", year: "2001" }, { name: "Isadora: De Além da Ilusão", first_air_date: "2023-08-01" }, false],
  [{ name: "Dune: Part One", year: "2021" }, { title: "Dune", release_date: "2021-09-15" }, true],
  [{ name: "The Lord of the Rings: The Two Towers", year: "2002" }, { title: "The Lord of the Rings: The Two Towers", release_date: "2002-12-18" }, true],
  [{ name: "Dark Matter", year: "2024–" }, { name: "Dark Matter", first_air_date: "2015-06-12" }, false],
  [{ name: "Amélie", year: "2001" }, { title: "Amelie", original_title: "Le Fabuleux Destin d'Amélie Poulain", release_date: "2001-04-25" }, true],
  [{ name: "Fast & Furious 6", year: "2013" }, { title: "Fast and Furious 6", release_date: "2013-05-21" }, true],
  [{ name: "Planet Earth", year: "2006" }, { name: "Growing Exposed", first_air_date: "2018-01-01" }, false],
  [{ name: "Palace", year: "2026–" }, { name: "Under Siege", first_air_date: "2026-11-06" }, true],
  [{ name: "The Chosen", year: "2017–" }, { name: "The Chosen", first_air_date: "2019-04-21" }, true],
  [{ name: null, year: null }, { name: "The Rules", first_air_date: "2026-10-28" }, true],
];
let bad = 0;
for (const [imdb, tmdb, want] of cases) {
  const got = sameTitle(imdb, tmdb);
  console.log(`${got === want ? "  ✓" : "  ✗"} ${imdb.name} / ${tmdb.title || tmdb.name}: ${got}`);
  if (got !== want) bad++;
}
console.log(bad ? `${bad} failed` : "all passed");
process.exit(bad ? 1 : 0);
