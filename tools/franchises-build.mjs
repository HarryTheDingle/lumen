// node tools/franchises-build.mjs: writes ios/Dxbi/Franchises.json, the franchises and filmmakers Discover
// shows without a TMDB key. The lists below are names and years; each is looked up at Cinemeta (which is what
// the app opens titles with), and only what Cinemeta has under that name and year is kept, with its IMDb id.
// A filmmaker's film is kept only when Cinemeta credits them as its director or writer. TMDB's person ids
// come from Wikidata (by name, for someone who works in film). Whatever isn't found is listed and left out.
import { writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const CINEMETA = "https://v3-cinemeta.strem.io";

// A title: "Name" (the year follows), or ["Name", "another name it goes by", ...]
// [art]: the film whose backdrop is the tile's picture (the first, unless said). [seeds]: the films whose
// TMDB collections fill the franchise in with a key (the first, unless said; []: the list here is all of it)
const FRANCHISES = [
  { id: "bond", name: "James Bond", art: "Skyfall", films: [["Dr. No", 1962], ["From Russia with Love", 1963], ["Goldfinger", 1964], ["Thunderball", 1965], ["You Only Live Twice", 1967], ["On Her Majesty's Secret Service", 1969], ["Diamonds Are Forever", 1971], ["Live and Let Die", 1973], ["The Man with the Golden Gun", 1974], ["The Spy Who Loved Me", 1977], ["Moonraker", 1979], ["For Your Eyes Only", 1981], ["Octopussy", 1983], ["A View to a Kill", 1985], ["The Living Daylights", 1987], ["Licence to Kill", 1989], ["GoldenEye", 1995], ["Tomorrow Never Dies", 1997], ["The World Is Not Enough", 1999], ["Die Another Day", 2002], ["Casino Royale", 2006], ["Quantum of Solace", 2008], ["Skyfall", 2012], ["Spectre", 2015], ["No Time to Die", 2021]] },
  { id: "star-wars", name: "Star Wars", art: "Star Wars: Episode V - The Empire Strikes Back", films: [[["Star Wars: Episode IV - A New Hope", "Star Wars"], 1977], [["Star Wars: Episode V - The Empire Strikes Back", "The Empire Strikes Back"], 1980], [["Star Wars: Episode VI - Return of the Jedi", "Return of the Jedi"], 1983], ["Star Wars: Episode I - The Phantom Menace", 1999], ["Star Wars: Episode II - Attack of the Clones", 2002], ["Star Wars: Episode III - Revenge of the Sith", 2005], [["Star Wars: Episode VII - The Force Awakens", "Star Wars: The Force Awakens"], 2015], [["Rogue One: A Star Wars Story", "Rogue One"], 2016], [["Star Wars: Episode VIII - The Last Jedi", "Star Wars: The Last Jedi"], 2017], ["Solo: A Star Wars Story", 2018], [["Star Wars: Episode IX - The Rise of Skywalker", "Star Wars: The Rise of Skywalker"], 2019]], series: [["The Mandalorian", 2019], [["Andor", "Star Wars: Andor"], 2022]] },
  { id: "marvel", name: "Marvel Cinematic Universe", art: "The Avengers", seeds: [], films: [["Iron Man", 2008], ["The Incredible Hulk", 2008], ["Iron Man 2", 2010], ["Thor", 2011], ["Captain America: The First Avenger", 2011], ["The Avengers", 2012], ["Iron Man 3", 2013], ["Thor: The Dark World", 2013], ["Captain America: The Winter Soldier", 2014], ["Guardians of the Galaxy", 2014], ["Avengers: Age of Ultron", 2015], ["Ant-Man", 2015], ["Captain America: Civil War", 2016], ["Doctor Strange", 2016], ["Guardians of the Galaxy Vol. 2", 2017], ["Spider-Man: Homecoming", 2017], ["Thor: Ragnarok", 2017], ["Black Panther", 2018], ["Avengers: Infinity War", 2018], ["Ant-Man and the Wasp", 2018], ["Captain Marvel", 2019], ["Avengers: Endgame", 2019], ["Spider-Man: Far from Home", 2019], ["Black Widow", 2021], ["Shang-Chi and the Legend of the Ten Rings", 2021], ["Eternals", 2021], ["Spider-Man: No Way Home", 2021], ["Doctor Strange in the Multiverse of Madness", 2022], ["Thor: Love and Thunder", 2022], ["Black Panther: Wakanda Forever", 2022], ["Ant-Man and the Wasp: Quantumania", 2023], ["Guardians of the Galaxy Vol. 3", 2023], ["The Marvels", 2023], ["Deadpool & Wolverine", 2024], ["Captain America: Brave New World", 2025], [["Thunderbolts*", "Thunderbolts"], 2025], ["The Fantastic Four: First Steps", 2025]], series: [["WandaVision", 2021], ["Loki", 2021]] },
  { id: "harry-potter", name: "Harry Potter", films: [[["Harry Potter and the Sorcerer's Stone", "Harry Potter and the Philosopher's Stone"], 2001], ["Harry Potter and the Chamber of Secrets", 2002], ["Harry Potter and the Prisoner of Azkaban", 2004], ["Harry Potter and the Goblet of Fire", 2005], ["Harry Potter and the Order of the Phoenix", 2007], ["Harry Potter and the Half-Blood Prince", 2009], [["Harry Potter and the Deathly Hallows: Part 1", "Harry Potter and the Deathly Hallows - Part 1"], 2010], [["Harry Potter and the Deathly Hallows: Part 2", "Harry Potter and the Deathly Hallows - Part 2"], 2011]] },
  { id: "middle-earth", name: "Middle-earth", seeds: ["The Lord of the Rings: The Fellowship of the Ring", "The Hobbit: An Unexpected Journey"], films: [["The Lord of the Rings: The Fellowship of the Ring", 2001], ["The Lord of the Rings: The Two Towers", 2002], ["The Lord of the Rings: The Return of the King", 2003], ["The Hobbit: An Unexpected Journey", 2012], ["The Hobbit: The Desolation of Smaug", 2013], ["The Hobbit: The Battle of the Five Armies", 2014]], series: [["The Lord of the Rings: The Rings of Power", 2022]] },
  { id: "mission-impossible", name: "Mission: Impossible", art: "Mission: Impossible - Fallout", films: [["Mission: Impossible", 1996], [["Mission: Impossible II", "Mission: Impossible 2"], 2000], ["Mission: Impossible III", 2006], ["Mission: Impossible - Ghost Protocol", 2011], ["Mission: Impossible - Rogue Nation", 2015], ["Mission: Impossible - Fallout", 2018], [["Mission: Impossible - Dead Reckoning Part One", "Mission: Impossible - Dead Reckoning"], 2023], ["Mission: Impossible - The Final Reckoning", 2025]] },
  { id: "bourne", name: "Bourne", films: [["The Bourne Identity", 2002], ["The Bourne Supremacy", 2004], ["The Bourne Ultimatum", 2007], ["The Bourne Legacy", 2012], ["Jason Bourne", 2016]] },
  { id: "jurassic", name: "Jurassic Park", films: [["Jurassic Park", 1993], ["The Lost World: Jurassic Park", 1997], ["Jurassic Park III", 2001], ["Jurassic World", 2015], ["Jurassic World: Fallen Kingdom", 2018], [["Jurassic World Dominion", "Jurassic World: Dominion"], 2022], [["Jurassic World Rebirth", "Jurassic World: Rebirth"], 2025]] },
  { id: "alien", name: "Alien", films: [["Alien", 1979], ["Aliens", 1986], [["Alien³", "Alien 3", "Alien3"], 1992], [["Alien Resurrection", "Alien: Resurrection"], 1997], ["Prometheus", 2012], ["Alien: Covenant", 2017], ["Alien: Romulus", 2024]], series: [["Alien: Earth", 2025]] },
  { id: "indiana-jones", name: "Indiana Jones", films: [[["Raiders of the Lost Ark", "Indiana Jones and the Raiders of the Lost Ark"], 1981], ["Indiana Jones and the Temple of Doom", 1984], ["Indiana Jones and the Last Crusade", 1989], ["Indiana Jones and the Kingdom of the Crystal Skull", 2008], ["Indiana Jones and the Dial of Destiny", 2023]] },
  { id: "fast", name: "Fast & Furious", art: "Fast Five", films: [["The Fast and the Furious", 2001], ["2 Fast 2 Furious", 2003], ["The Fast and the Furious: Tokyo Drift", 2006], ["Fast & Furious", 2009], ["Fast Five", 2011], ["Fast & Furious 6", 2013], [["Furious 7", "Furious Seven", "Fast & Furious 7"], 2015], [["The Fate of the Furious", "Fast & Furious 8"], 2017], [["F9", "F9: The Fast Saga", "Fast & Furious 9"], 2021], ["Fast X", 2023]] },
  { id: "john-wick", name: "John Wick", films: [["John Wick", 2014], ["John Wick: Chapter 2", 2017], ["John Wick: Chapter 3 - Parabellum", 2019], ["John Wick: Chapter 4", 2023], [["Ballerina", "From the World of John Wick: Ballerina"], 2025]] },
  { id: "dune", name: "Dune", art: "Dune: Part Two", films: [[["Dune", "Dune: Part One"], 2021], ["Dune: Part Two", 2024]], series: [["Dune: Prophecy", 2024]] },
  { id: "batman", name: "Batman", art: "The Dark Knight", seeds: ["Batman", "Batman Begins", "The Batman"], films: [["Batman", 1989], ["Batman Returns", 1992], ["Batman Forever", 1995], ["Batman & Robin", 1997], ["Batman Begins", 2005], ["The Dark Knight", 2008], ["The Dark Knight Rises", 2012], ["The Batman", 2022]] },
  { id: "toy-story", name: "Toy Story", films: [["Toy Story", 1995], ["Toy Story 2", 1999], ["Toy Story 3", 2010], ["Toy Story 4", 2019]] },
  { id: "matrix", name: "The Matrix", films: [["The Matrix", 1999], ["The Matrix Reloaded", 2003], ["The Matrix Revolutions", 2003], ["The Matrix Resurrections", 2021]] },
  { id: "apes", name: "Planet of the Apes", art: "War for the Planet of the Apes", films: [["Rise of the Planet of the Apes", 2011], ["Dawn of the Planet of the Apes", 2014], ["War for the Planet of the Apes", 2017], ["Kingdom of the Planet of the Apes", 2024]] },
  { id: "hunger-games", name: "The Hunger Games", art: "The Hunger Games: Catching Fire", films: [["The Hunger Games", 2012], ["The Hunger Games: Catching Fire", 2013], ["The Hunger Games: Mockingjay - Part 1", 2014], ["The Hunger Games: Mockingjay - Part 2", 2015], ["The Hunger Games: The Ballad of Songbirds & Snakes", 2023]] },
  { id: "back-to-the-future", name: "Back to the Future", films: [["Back to the Future", 1985], ["Back to the Future Part II", 1989], ["Back to the Future Part III", 1990]] },
  { id: "godfather", name: "The Godfather", films: [["The Godfather", 1972], [["The Godfather Part II", "The Godfather: Part II"], 1974], [["The Godfather Part III", "The Godfather: Part III"], 1990]] },
  { id: "mad-max", name: "Mad Max", art: "Mad Max: Fury Road", seeds: ["Mad Max", "Furiosa: A Mad Max Saga"], films: [["Mad Max", 1979], [["Mad Max 2", "Mad Max 2: The Road Warrior", "The Road Warrior"], 1981], [["Mad Max Beyond Thunderdome", "Mad Max: Beyond Thunderdome"], 1985], ["Mad Max: Fury Road", 2015], ["Furiosa: A Mad Max Saga", 2024]] },
  { id: "terminator", name: "Terminator", art: "Terminator 2: Judgment Day", films: [["The Terminator", 1984], ["Terminator 2: Judgment Day", 1991], ["Terminator 3: Rise of the Machines", 2003], ["Terminator Salvation", 2009], ["Terminator Genisys", 2015], ["Terminator: Dark Fate", 2019]] },
  { id: "pirates", name: "Pirates of the Caribbean", films: [["Pirates of the Caribbean: The Curse of the Black Pearl", 2003], ["Pirates of the Caribbean: Dead Man's Chest", 2006], ["Pirates of the Caribbean: At World's End", 2007], ["Pirates of the Caribbean: On Stranger Tides", 2011], [["Pirates of the Caribbean: Dead Men Tell No Tales", "Pirates of the Caribbean: Salazar's Revenge"], 2017]] },
  { id: "rocky", name: "Rocky & Creed", seeds: ["Rocky", "Creed"], films: [["Rocky", 1976], ["Rocky II", 1979], ["Rocky III", 1982], ["Rocky IV", 1985], ["Rocky V", 1990], ["Rocky Balboa", 2006], ["Creed", 2015], ["Creed II", 2018], ["Creed III", 2023]] },
  { id: "oceans", name: "Ocean's", films: [["Ocean's Eleven", 2001], ["Ocean's Twelve", 2004], ["Ocean's Thirteen", 2007], [["Ocean's Eight", "Ocean's 8"], 2018]] },
  { id: "knives-out", name: "Knives Out", films: [["Knives Out", 2019], [["Glass Onion", "Glass Onion: A Knives Out Mystery"], 2022], [["Wake Up Dead Man", "Wake Up Dead Man: A Knives Out Mystery"], 2025]] },
  { id: "top-gun", name: "Top Gun", art: "Top Gun: Maverick", films: [["Top Gun", 1986], ["Top Gun: Maverick", 2022]] },
  { id: "avatar", name: "Avatar", films: [["Avatar", 2009], ["Avatar: The Way of Water", 2022], ["Avatar: Fire and Ash", 2025]] },
  { id: "paddington", name: "Paddington", art: "Paddington 2", films: [["Paddington", 2014], ["Paddington 2", 2017], ["Paddington in Peru", 2024]] },
  { id: "blade-runner", name: "Blade Runner", art: "Blade Runner 2049", films: [["Blade Runner", 1982], ["Blade Runner 2049", 2017]] },
  { id: "kingsman", name: "Kingsman", films: [["Kingsman: The Secret Service", 2014], ["Kingsman: The Golden Circle", 2017], ["The King's Man", 2021]] },
  { id: "die-hard", name: "Die Hard", films: [["Die Hard", 1988], [["Die Hard 2", "Die Hard 2: Die Harder"], 1990], [["Die Hard with a Vengeance", "Die Hard: With a Vengeance"], 1995], ["Live Free or Die Hard", 2007], ["A Good Day to Die Hard", 2013]] },
];

// [credit]: the names Cinemeta credits them under, when it isn't [name]. [role]: what the tile says they do
const FILMMAKERS = [
  { name: "Christopher Nolan", role: "Director", films: [["Memento", 2000], ["Insomnia", 2002], ["Batman Begins", 2005], ["The Prestige", 2006], ["The Dark Knight", 2008], ["Inception", 2010], ["The Dark Knight Rises", 2012], ["Interstellar", 2014], ["Dunkirk", 2017], ["Tenet", 2020], ["Oppenheimer", 2023]] },
  { name: "Denis Villeneuve", role: "Director", films: [["Incendies", 2010], ["Prisoners", 2013], ["Enemy", 2013], ["Sicario", 2015], ["Arrival", 2016], ["Blade Runner 2049", 2017], [["Dune", "Dune: Part One"], 2021], ["Dune: Part Two", 2024]] },
  { name: "Steven Spielberg", role: "Director", films: [["Jaws", 1975], ["Close Encounters of the Third Kind", 1977], [["Raiders of the Lost Ark", "Indiana Jones and the Raiders of the Lost Ark"], 1981], ["E.T. the Extra-Terrestrial", 1982], ["Jurassic Park", 1993], ["Schindler's List", 1993], ["Saving Private Ryan", 1998], ["Minority Report", 2002], ["Catch Me If You Can", 2002], ["Munich", 2005], ["Lincoln", 2012], ["Bridge of Spies", 2015], ["The Fabelmans", 2022]] },
  { name: "Martin Scorsese", role: "Director", films: [["Taxi Driver", 1976], ["Raging Bull", 1980], ["Goodfellas", 1990], ["Casino", 1995], ["Gangs of New York", 2002], ["The Aviator", 2004], ["The Departed", 2006], ["Shutter Island", 2010], ["The Wolf of Wall Street", 2013], ["The Irishman", 2019], ["Killers of the Flower Moon", 2023]] },
  { name: "Quentin Tarantino", role: "Writer & Director", films: [["Reservoir Dogs", 1992], ["Pulp Fiction", 1994], ["Jackie Brown", 1997], ["Kill Bill: Vol. 1", 2003], ["Kill Bill: Vol. 2", 2004], ["Death Proof", 2007], ["Inglourious Basterds", 2009], ["Django Unchained", 2012], ["The Hateful Eight", 2015], [["Once Upon a Time... in Hollywood", "Once Upon a Time in Hollywood", "Once Upon a Time… in Hollywood"], 2019]] },
  { name: "David Fincher", role: "Director", films: [[["Se7en", "Seven"], 1995], ["The Game", 1997], ["Fight Club", 1999], ["Panic Room", 2002], ["Zodiac", 2007], ["The Curious Case of Benjamin Button", 2008], ["The Social Network", 2010], ["The Girl with the Dragon Tattoo", 2011], ["Gone Girl", 2014], ["Mank", 2020], ["The Killer", 2023]] },
  { name: "Stanley Kubrick", role: "Director", films: [["Paths of Glory", 1957], ["Spartacus", 1960], ["Dr. Strangelove or: How I Learned to Stop Worrying and Love the Bomb", 1964], ["2001: A Space Odyssey", 1968], ["A Clockwork Orange", 1971], ["Barry Lyndon", 1975], ["The Shining", 1980], ["Full Metal Jacket", 1987], ["Eyes Wide Shut", 1999]] },
  { name: "Ridley Scott", role: "Director", films: [["Alien", 1979], ["Blade Runner", 1982], ["Thelma & Louise", 1991], ["Gladiator", 2000], ["Black Hawk Down", 2001], ["Kingdom of Heaven", 2005], ["American Gangster", 2007], ["Prometheus", 2012], ["The Martian", 2015], ["The Last Duel", 2021], ["Napoleon", 2023], ["Gladiator II", 2024]] },
  { name: "Wes Anderson", role: "Writer & Director", films: [["Rushmore", 1998], ["The Royal Tenenbaums", 2001], ["The Life Aquatic with Steve Zissou", 2004], ["The Darjeeling Limited", 2007], ["Fantastic Mr. Fox", 2009], ["Moonrise Kingdom", 2012], ["The Grand Budapest Hotel", 2014], ["Isle of Dogs", 2018], ["The French Dispatch", 2021], ["Asteroid City", 2023], ["The Phoenician Scheme", 2025]] },
  { name: "The Coen Brothers", person: "Joel Coen", wiki: "Coen brothers", credit: ["Joel Coen", "Ethan Coen"], role: "Writers & Directors", films: [["Blood Simple", 1984], ["Raising Arizona", 1987], ["Miller's Crossing", 1990], ["Barton Fink", 1991], ["Fargo", 1996], ["The Big Lebowski", 1998], ["O Brother, Where Art Thou?", 2000], ["No Country for Old Men", 2007], ["Burn After Reading", 2008], ["A Serious Man", 2009], ["True Grit", 2010], ["Inside Llewyn Davis", 2013]] },
  { name: "Greta Gerwig", role: "Writer & Director", films: [["Frances Ha", 2012], ["Lady Bird", 2017], ["Little Women", 2019], ["Barbie", 2023]] },
  { name: "Bong Joon-ho", credit: ["Bong Joon Ho", "Bong Joon-ho", "Joon-ho Bong", "Joon Ho Bong"], role: "Writer & Director", films: [["Memories of Murder", 2003], ["The Host", 2006], ["Mother", 2009], ["Snowpiercer", 2013], ["Okja", 2017], ["Parasite", 2019], ["Mickey 17", 2025]] },
  { name: "Aaron Sorkin", role: "Writer", films: [["A Few Good Men", 1992], ["The American President", 1995], ["Charlie Wilson's War", 2007], ["The Social Network", 2010], ["Moneyball", 2011], ["Steve Jobs", 2015], ["Molly's Game", 2017], ["The Trial of the Chicago 7", 2020], ["Being the Ricardos", 2021]], series: [["The West Wing", 1999], ["The Newsroom", 2012]] },
  { name: "Charlie Kaufman", role: "Writer", films: [["Being John Malkovich", 1999], [["Adaptation.", "Adaptation"], 2002], ["Eternal Sunshine of the Spotless Mind", 2004], ["Synecdoche, New York", 2008], ["Anomalisa", 2015], ["I'm Thinking of Ending Things", 2020]] },
  { name: "Taylor Sheridan", role: "Writer", films: [["Sicario", 2015], ["Hell or High Water", 2016], ["Wind River", 2017], ["Sicario: Day of the Soldado", 2018], ["Those Who Wish Me Dead", 2021]], series: [["Yellowstone", 2018], ["1883", 2021], ["Landman", 2024]] },
  { name: "James Cameron", role: "Director", films: [["The Terminator", 1984], ["Aliens", 1986], ["The Abyss", 1989], ["Terminator 2: Judgment Day", 1991], ["True Lies", 1994], ["Titanic", 1997], ["Avatar", 2009], ["Avatar: The Way of Water", 2022]] },
  { name: "Paul Thomas Anderson", role: "Writer & Director", films: [["Boogie Nights", 1997], ["Magnolia", 1999], ["Punch-Drunk Love", 2002], ["There Will Be Blood", 2007], ["The Master", 2012], ["Phantom Thread", 2017], ["Licorice Pizza", 2021], ["One Battle After Another", 2025]] },
  { name: "Edgar Wright", role: "Writer & Director", films: [["Shaun of the Dead", 2004], ["Hot Fuzz", 2007], ["Scott Pilgrim vs. the World", 2010], ["The World's End", 2013], ["Baby Driver", 2017], ["Last Night in Soho", 2021]] },
  { name: "Alfred Hitchcock", role: "Director", films: [["Strangers on a Train", 1951], ["Dial M for Murder", 1954], ["Rear Window", 1954], ["Vertigo", 1958], ["North by Northwest", 1959], ["Psycho", 1960], ["The Birds", 1963]] },
  { name: "Guillermo del Toro", role: "Writer & Director", films: [["Hellboy", 2004], ["Pan's Labyrinth", 2006], ["Pacific Rim", 2013], ["The Shape of Water", 2017], ["Nightmare Alley", 2021], ["Frankenstein", 2025]] },
  { name: "Jordan Peele", role: "Writer & Director", films: [["Get Out", 2017], ["Us", 2019], ["Nope", 2022]] },
  { name: "Alex Garland", role: "Writer & Director", films: [["28 Days Later", 2002], ["Sunshine", 2007], ["Ex Machina", 2014], ["Annihilation", 2018], ["Civil War", 2024]] },
  { name: "Martin McDonagh", role: "Writer & Director", films: [["In Bruges", 2008], ["Seven Psychopaths", 2012], ["Three Billboards Outside Ebbing, Missouri", 2017], ["The Banshees of Inisherin", 2022]] },
  { name: "Danny Boyle", role: "Director", films: [["Trainspotting", 1996], ["28 Days Later", 2002], ["Sunshine", 2007], ["Slumdog Millionaire", 2008], ["127 Hours", 2010], ["Steve Jobs", 2015], ["28 Years Later", 2025]] },
  { name: "Francis Ford Coppola", role: "Director", films: [["The Godfather", 1972], ["The Conversation", 1974], [["The Godfather Part II", "The Godfather: Part II"], 1974], ["Apocalypse Now", 1979]] },
  { name: "Hayao Miyazaki", role: "Writer & Director", films: [["My Neighbor Totoro", 1988], ["Princess Mononoke", 1997], ["Spirited Away", 2001], ["Howl's Moving Castle", 2004], ["The Boy and the Heron", 2023]] },
];

const norm = (t) => String(t || "").normalize("NFKD").toLowerCase().replace(/&/g, "and").replace(/[^a-z0-9]+/g, "");
const names = (t) => (Array.isArray(t[0]) ? t[0] : [t[0]]);
const dropped = [];

async function json(url) {
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const r = await fetch(url, { signal: AbortSignal.timeout(20000) });
      if (r.ok) return await r.json();
    } catch (_) { /* tried again */ }
  }
  return null;
}

const found = new Map();
// The title at Cinemeta: searched by each of its names, kept when a result has one of them and the year
// (a year either side when nothing has: a festival year), then read back by its id to be sure of it
async function lookup(type, t) {
  const key = `${type}:${names(t).join("|")}:${t[1]}`;
  if (found.has(key)) return found.get(key);
  const p = (async () => {
    const want = new Set(names(t).map(norm));
    const hits = [];
    for (const name of names(t)) {
      const list = await json(`${CINEMETA}/catalog/${type}/top/search=${encodeURIComponent(name)}.json`);
      for (const m of (list && list.metas) || []) if (want.has(norm(m.name)) && /^tt\d+$/.test(m.id)) hits.push(m);
      if (hits.some((m) => String(m.releaseInfo || "").startsWith(String(t[1])))) break;
    }
    const year = (m) => parseInt(String(m.releaseInfo || ""), 10);
    const hit = hits.find((m) => year(m) === t[1]) || hits.find((m) => Math.abs(year(m) - t[1]) === 1);
    if (!hit) return null;
    const meta = ((await json(`${CINEMETA}/meta/${type}/${hit.id}.json`)) || {}).meta;
    if (!meta || !want.has(norm(meta.name))) return null;
    return { id: hit.id, name: meta.name, year: parseInt(String(meta.releaseInfo || meta.year || t[1]), 10) || t[1], director: meta.director || [], writer: meta.writer || [] };
  })();
  found.set(key, p);
  return p;
}

async function list(type, titles, owner, credit) {
  const out = [];
  for (let i = 0; i < titles.length; i += 6) {
    const batch = await Promise.all(titles.slice(i, i + 6).map((t) => lookup(type, t)));
    batch.forEach((hit, j) => {
      const t = titles[i + j];
      if (!hit) return dropped.push(`${owner}: ${names(t)[0]} (${t[1]}) isn't at Cinemeta under that name and year`);
      if (credit) {
        const people = [...hit.director, ...hit.writer].map(norm);
        if (!credit.some((c) => people.includes(norm(c)))) return dropped.push(`${owner}: ${hit.name} (${hit.id}) doesn't credit them at Cinemeta (${[...hit.director, ...hit.writer].join(", ")})`);
      }
      out.push({ id: hit.id, name: hit.name, year: hit.year });
    });
  }
  return out;
}

// TMDB's id for someone, from Wikidata: the person of that name who has one and works in film
async function tmdbPerson(name) {
  const query = `SELECT ?tmdb WHERE { ?p rdfs:label ${JSON.stringify(name)}@en; wdt:P4985 ?tmdb; wdt:P106 ?job. VALUES ?job { wd:Q2526255 wd:Q28389 wd:Q69423232 wd:Q3455803 } } LIMIT 5`;
  try {
    const r = await fetch(`https://query.wikidata.org/sparql?format=json&query=${encodeURIComponent(query)}`, { headers: { "user-agent": "franchises-build/1.0 (list builder)" }, signal: AbortSignal.timeout(30000) });
    const rows = r.ok ? (await r.json()).results.bindings : [];
    const ids = [...new Set(rows.map((b) => b.tmdb.value))];
    return ids.length === 1 ? ids[0] : null;
  } catch (_) {
    return null;
  }
}

const franchises = [];
for (const f of FRANCHISES) {
  const films = await list("movie", f.films, f.name);
  const series = await list("series", f.series || [], f.name);
  if (films.length < 2) { dropped.push(`${f.name}: left out (fewer than two films found)`); continue; }
  const byName = (n) => films.find((x) => x.name === n || f.films.some((t) => names(t).includes(n) && names(t).map(norm).includes(norm(x.name))));
  const art = (f.art && byName(f.art)) || films[0];
  if (f.art && art === films[0] && norm(films[0].name) !== norm(f.art)) dropped.push(`${f.name}: its picture's film (${f.art}) wasn't found, the first is used`);
  const seeds = (f.seeds || [f.films[0][0]].flat().slice(0, 1)).map((n) => byName(n)).filter(Boolean).map((x) => x.id);
  franchises.push({ id: f.id, name: f.name, art: art.id, seeds, films, series });
  console.log(`${f.name}: ${films.length}/${f.films.length} films, ${series.length} series`);
}

const filmmakers = [];
for (const p of FILMMAKERS) {
  const credit = p.credit || [p.name];
  const films = await list("movie", p.films, p.name, credit);
  const series = await list("series", p.series || [], p.name);
  if (films.length < 2) { dropped.push(`${p.name}: left out (fewer than two films found)`); continue; }
  const person = p.person || p.name;
  const tmdb = await tmdbPerson(person);
  if (!tmdb) dropped.push(`${p.name}: no single TMDB id at Wikidata (found by name instead)`);
  filmmakers.push({ id: norm(p.name), name: p.name, person, tmdb: tmdb || "", wiki: p.wiki || person, role: p.role, films, series });
  console.log(`${p.name}: ${films.length}/${p.films.length} films, ${series.length} series, TMDB ${tmdb || "?"}`);
}

writeFileSync(join(root, "ios/Dxbi/Franchises.json"), JSON.stringify({ franchises, filmmakers }, null, 1) + "\n");
console.log(`\nWrote ${franchises.length} franchises and ${filmmakers.length} filmmakers.`);
if (dropped.length) console.log(`\nLeft out:\n  ${dropped.join("\n  ")}`);
