#!/usr/bin/env python3
"""Daily NYFW/NYC modeling event monitor used by GitHub Actions."""

from __future__ import annotations

import hashlib
import html
import json
import os
import re
import sys
from dataclasses import dataclass
from datetime import date, datetime
from urllib.parse import parse_qsl, quote_plus, urlencode, urljoin, urlsplit, urlunsplit
from zoneinfo import ZoneInfo

import feedparser
import requests
from bs4 import BeautifulSoup

END_DATE = date(2026, 9, 15)
EASTERN = ZoneInfo("America/New_York")
MAX_ALERTS = 15
ISSUE_STATE_RE = re.compile(r"<!--\s*NYFW_MONITOR_STATE\s*(\{.*?\})\s*-->", re.S)
DOLLAR_RE = re.compile(r"\$(\d{1,3})(?:\.\d{1,2})?")
UA = "Mozilla/5.0 (compatible; NYFWEventMonitor/1.0)"

QUERIES = [
    "NYFW 2026 free RSVP New York",
    '"New York Fashion Week" free events September 2026',
    "NYFW 2026 open model casting New York",
    '"model casting" New York September 2026 free',
    "site:eventbrite.com New York Fashion Week free September 2026",
    "site:lu.ma NYFW NYC September 2026",
    "site:partiful.com NYFW NYC September 2026",
    "site:fashionweekonline.com/calendar NYFW September 2026",
    "NYFW volunteer models New York 2026",
    "NYFW panel networking free New York 2026",
]

DIRECT_PAGES = [
    ("Fashion Week Online NYC calendar", "https://fashionweekonline.com/calendar"),
    ("Eventbrite NYC fashion week", "https://www.eventbrite.com/d/ny--new-york/fashion-week/"),
    ("Eventbrite NYC modeling", "https://www.eventbrite.com/d/ny--new-york/modeling/"),
    ("Rockefeller Center New York Fashion Week", "https://www.rockefellercenter.com/magazine/events/new-york-fashion-week-events/"),
]

EVENT_TERMS = (
    "nyfw", "fashion week", "model", "modeling", "runway", "casting",
    "fashion show", "designer showcase", "fashion presentation", "catwalk",
    "fashion networking", "fashion panel", "fashion pop-up", "fashion popup",
    "fashion mixer", "fashion week party",
)
NYC_TERMS = (
    "new york", "nyc", "manhattan", "brooklyn", "queens", "bronx",
    "times square", "rockefeller", "soho", "chelsea", "midtown",
    "lower east side", "harlem",
)
FREE_TERMS = ("free", "complimentary", "no cost", "no cover", "free rsvp", "$0")
CASTING_TERMS = ("volunteer", "open casting", "model casting", "casting call", "audition")
SCAM_TERMS = (
    "application fee", "modeling fee", "required photographer",
    "required photoshoot", "portfolio package", "training fee", "wardrobe fee",
    "pay to participate", "pay to walk", "secure your runway spot",
    "deposit required",
)
TRUSTED_DOMAINS = (
    "eventbrite.com", "lu.ma", "partiful.com", "fashionweekonline.com",
    "rockefellercenter.com", "cfda.com", "nyfw.com", "1iota.com",
    "backstage.com",
)


@dataclass(frozen=True)
class Candidate:
    title: str
    url: str
    snippet: str
    source: str
    published: str = ""


def clean(value: str | None) -> str:
    text = html.unescape(value or "")
    text = re.sub(r"<[^>]+>", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def canonical(url: str) -> str:
    p = urlsplit(url)
    kept = [
        (k, v) for k, v in parse_qsl(p.query, keep_blank_values=True)
        if not k.lower().startswith(("utm_", "fbclid", "gclid", "mc_"))
    ]
    return urlunsplit((p.scheme.lower(), p.netloc.lower(), p.path.rstrip("/") or "/", urlencode(kept), ""))


def item_id(item: Candidate) -> str:
    raw = f"{canonical(item.url)}|{item.title.lower().strip()}"
    return hashlib.sha256(raw.encode()).hexdigest()[:24]


def get(session: requests.Session, url: str) -> requests.Response:
    response = session.get(url, timeout=18, allow_redirects=True)
    response.raise_for_status()
    return response


def collect() -> list[Candidate]:
    session = requests.Session()
    session.headers.update({"User-Agent": UA})
    found: list[Candidate] = []
    failures: list[str] = []

    feeds: list[tuple[str, str]] = []
    for query in QUERIES:
        feeds.append((f"Bing web: {query}", f"https://www.bing.com/search?format=rss&q={quote_plus(query)}"))
    for query in QUERIES[:5]:
        feeds.append((f"Google News: {query}", "https://news.google.com/rss/search?" f"q={quote_plus(query)}&hl=en-US&gl=US&ceid=US:en"))

    for source, url in feeds:
        try:
            parsed = feedparser.parse(get(session, url).content)
            for entry in parsed.entries:
                title = clean(entry.get("title"))
                link = clean(entry.get("link"))
                snippet = clean(entry.get("summary") or entry.get("description"))
                published = clean(entry.get("published") or entry.get("updated"))
                if title and link.startswith(("http://", "https://")):
                    found.append(Candidate(title, link, snippet, source, published))
        except Exception as exc:
            failures.append(f"{source}: {exc}")

    for source, url in DIRECT_PAGES:
        try:
            soup = BeautifulSoup(get(session, url).text, "html.parser")
            for anchor in soup.select("a[href]"):
                title = clean(anchor.get_text(" ", strip=True))
                link = urljoin(url, str(anchor.get("href")))
                if title and link.startswith(("http://", "https://")):
                    found.append(Candidate(title, link, f"Found on {source}.", source))
        except Exception as exc:
            failures.append(f"{source}: {exc}")

    unique: dict[str, Candidate] = {}
    for item in found:
        unique.setdefault(item_id(item), item)

    print(f"Collected {len(found)} raw results and {len(unique)} unique results.")
    if failures:
        print(f"{len(failures)} sources failed; other sources were still checked.")
        for failure in failures[:8]:
            print(f"- {failure}")
    return list(unique.values())


def classify(item: Candidate) -> tuple[bool, int, str, str]:
    text = clean(f"{item.title} {item.snippet} {item.url} {item.source}")
    lower = text.lower()

    if not any(term in lower for term in EVENT_TERMS):
        return False, 0, "", ""
    if not any(term in lower for term in NYC_TERMS):
        return False, 0, "", ""
    if any(year in lower for year in ("2023", "2024", "2025")) and "2026" not in lower:
        return False, 0, "", ""
    if "online only" in lower or "virtual only" in lower:
        return False, 0, "", ""

    amounts = [int(value) for value in DOLLAR_RE.findall(text)]
    is_free = any(term in lower for term in FREE_TERMS) and "not free" not in lower
    is_casting = any(term in lower for term in CASTING_TERMS)
    low_amounts = sorted({amount for amount in amounts if amount <= 40})

    if is_free:
        price = "Free/complimentary language found; verify at registration."
    elif low_amounts:
        price = f"A price of ${low_amounts[0]} (within the $40 limit) appears in the listing."
    elif is_casting:
        price = "Casting/volunteer listing; verify that participation requires no fee."
    elif "rsvp" in lower and not amounts:
        price = "RSVP listing with no price shown; verify that it is free."
    else:
        return False, 0, "", ""

    scam_hits = [term for term in SCAM_TERMS if term in lower]
    if scam_hits:
        risk = "CAUTION — possible fee/scam language: " + ", ".join(scam_hits[:3]) + ". Do not pay to be selected as a model."
    elif "casting" in lower or "audition" in lower or "model" in lower:
        risk = "No obvious upfront-fee phrase was found. Verify the organizer and never pay merely to be selected."
    else:
        risk = "No obvious modeling-fee warning phrase was found in the indexed text."

    score = 0
    score += 10 if is_free else 0
    score += 7 if low_amounts else 0
    score += 8 if ("casting" in lower or "audition" in lower) else 0
    score += 8 if "volunteer" in lower else 0
    score += 4 if "rsvp" in lower else 0
    score += 3 if "2026" in lower else 0
    score += 2 if ("september" in lower or " sep " in f" {lower} ") else 0
    score += 4 if any(domain in lower for domain in TRUSTED_DOMAINS) else 0
    score -= 6 if scam_hits else 0
    return True, score, price, risk


def gh(session: requests.Session, method: str, path: str, payload: dict | None = None):
    response = session.request(method, f"https://api.github.com{path}", json=payload, timeout=25)
    response.raise_for_status()
    return response.json() if response.content else {}


def read_state(body: str) -> dict:
    match = ISSUE_STATE_RE.search(body or "")
    if not match:
        return {"seen": [], "last_run": "", "finished": False}
    try:
        state = json.loads(match.group(1))
    except json.JSONDecodeError:
        state = {}
    state.setdefault("seen", [])
    state.setdefault("last_run", "")
    state.setdefault("finished", False)
    return state


def with_state(body: str, state: dict) -> str:
    marker = "<!-- NYFW_MONITOR_STATE\n" + json.dumps(state, separators=(",", ":"), sort_keys=True) + "\n-->"
    if ISSUE_STATE_RE.search(body or ""):
        return ISSUE_STATE_RE.sub(marker, body, count=1)
    return (body or "").rstrip() + "\n\n" + marker + "\n"


def esc(text: str) -> str:
    return text.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")


def alert_comment(matches: list[tuple[Candidate, int, str, str]], now: datetime) -> str:
    lines = [
        f"## New free/low-cost NYC model & NYFW matches — {now.strftime('%B %-d, %Y')}",
        "",
        f"@rmcmurrer81 I found **{len(matches)} new matching {'listing' if len(matches) == 1 else 'listings'}**. Check each registration page before traveling or paying anything.",
        "",
    ]
    for item, _score, price, risk in matches:
        domain = urlsplit(item.url).netloc or item.source
        lines += [
            f"### [{esc(item.title[:180])}]({item.url})",
            f"- **Price clue:** {price}",
            f"- **Source:** {domain}",
            f"- **Safety check:** {risk}",
        ]
        if item.published:
            lines.append(f"- **Published/indexed:** {esc(item.published[:80])}")
        if item.snippet:
            lines.append(f"- **Listing text:** {esc(clean(item.snippet)[:300])}")
        lines.append("")
    lines += [
        "The monitor checks daily and does not repeat unchanged URL/title combinations.",
        f"It stops searching after **{END_DATE.strftime('%B %-d, %Y')}**.",
    ]
    return "\n".join(lines)


def main() -> int:
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    issue_number = os.environ.get("MONITOR_ISSUE", "")
    token = os.environ.get("GITHUB_TOKEN", "")
    if "/" not in repository or not issue_number.isdigit() or not token:
        raise RuntimeError("Required GitHub environment values are missing")

    owner, repo = repository.split("/", 1)
    issue_path = f"/repos/{owner}/{repo}/issues/{issue_number}"
    session = requests.Session()
    session.headers.update({
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": UA,
    })

    now = datetime.now(EASTERN)
    issue = gh(session, "GET", issue_path)
    body = str(issue.get("body") or "")
    state = read_state(body)

    if now.date() > END_DATE:
        if not state["finished"]:
            gh(session, "POST", f"{issue_path}/comments", {"body": f"@rmcmurrer81 The monitor has finished. It searched daily through **{END_DATE.strftime('%B %-d, %Y')}** and will perform no more event searches."})
            state.update({"finished": True, "last_run": now.isoformat()})
            gh(session, "PATCH", issue_path, {"body": with_state(body, state), "state": "closed", "state_reason": "completed"})
        print("End date passed; no search performed.")
        return 0

    if issue.get("state") == "closed":
        gh(session, "PATCH", issue_path, {"state": "open"})

    seen = {str(value) for value in state["seen"]}
    scored: list[tuple[Candidate, int, str, str, str]] = []
    for item in collect():
        relevant, score, price, risk = classify(item)
        identifier = item_id(item)
        if relevant and identifier not in seen:
            scored.append((item, score, price, risk, identifier))

    scored.sort(key=lambda value: (-value[1], value[0].title.lower()))
    selected = scored[:MAX_ALERTS]
    if selected:
        gh(session, "POST", f"{issue_path}/comments", {"body": alert_comment([(item, score, price, risk) for item, score, price, risk, _id in selected], now)})
        print(f"Posted {len(selected)} new alerts.")
    else:
        print("No new qualifying matches.")

    for _item, _score, _price, _risk, identifier in scored:
        seen.add(identifier)
    state.update({"seen": sorted(seen)[-1000:], "last_run": now.isoformat(), "finished": False})
    gh(session, "PATCH", issue_path, {"body": with_state(body, state)})
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"NYFW monitor failed: {exc}", file=sys.stderr)
        raise
