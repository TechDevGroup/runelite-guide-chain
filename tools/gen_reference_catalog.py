#!/usr/bin/env python3
"""One-shot extractor: consolidates the wiki-cited reference databases from
the sibling osrs-wiki repo into this repo's bundled catalog resource.

Sources (osrs-wiki repo, read-only — never read at guide-chain runtime):
  - assets/data/tools/quest_db.jsonl   kind: quest | diary
  - tools/wiki-kb/contrib.jsonl        rows keyed "minigamedb:*" / "unlockdb:*"

Output (this repo, committed + loaded from the classpath at construction by
ReferenceStore): src/main/resources/reference/catalog.jsonl — one JSON object
per line, uniform shape: {id, kind, name, reqs, rewards, refs, notes}.

Re-run whenever the source osrs-wiki corpus changes:
  python3 tools/gen_reference_catalog.py
"""
import json
import re

OSRS_WIKI = "/home/lemon/osrs-wiki"
QUEST_DB = f"{OSRS_WIKI}/assets/data/tools/quest_db.jsonl"
CONTRIB = f"{OSRS_WIKI}/tools/wiki-kb/contrib.jsonl"
OUT = "/home/lemon/runelite-guide-chain/src/main/resources/reference/catalog.jsonl"

DIARY_TIERS = {"easy", "medium", "hard", "elite"}


def read_jsonl(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            yield json.loads(line)


def humanize(slug):
    return re.sub(r"[-_]+", " ", slug).strip().title()


def ref_title(row):
    refs = row.get("refs") or []
    return refs[0].get("title") if refs and refs[0].get("title") else None


def dedupe_refs(refs):
    """Collapse repeated citations by url (fallback title), keeping first-seen
    order — a diary row cites its page once per tier, so refs arrive tripled.
    Belt-and-suspenders with the render-time dedupe in WebFragments/GuidePanel."""
    out, seen = [], set()
    for ref in refs or []:
        key = ref.get("url") or ref.get("title")
        if key is not None and key in seen:
            continue
        seen.add(key)
        out.append(ref)
    return out


def quest_name(row):
    title = ref_title(row)
    return title if title else humanize(row["id"])


def diary_name(row):
    title = ref_title(row)
    base = title if title else humanize(row["id"].rsplit("-", 1)[0])
    parts = row["id"].rsplit("-", 1)
    tier = parts[-1] if len(parts) == 2 and parts[-1] in DIARY_TIERS else None
    return f"{base} ({tier.capitalize()})" if tier else base


def load_quest_db():
    rows = []
    for row in read_jsonl(QUEST_DB):
        kind = row.get("kind")
        if kind not in ("quest", "diary"):
            continue
        name = diary_name(row) if kind == "diary" else quest_name(row)
        rows.append({
            "id": row["id"],
            "kind": kind,
            "name": name,
            "reqs": row.get("reqs"),
            "rewards": row.get("rewards"),
            "refs": dedupe_refs(row.get("refs")),
            "notes": row.get("notes"),
        })
    return rows


def load_contrib_db():
    rows = []
    for row in read_jsonl(CONTRIB):
        key = row.get("key") or ""
        if key.startswith("minigamedb:"):
            kind, slug = "minigame", key[len("minigamedb:"):]
            reqs_field, rewards_field = "reqs", "rewards"
        elif key.startswith("unlockdb:"):
            kind, slug = "unlock", key[len("unlockdb:"):]
            reqs_field, rewards_field = "gate", "grants"
        else:
            continue
        title = ref_title(row)
        rows.append({
            "id": slug,
            "kind": kind,
            "name": title if title else humanize(slug),
            "reqs": row.get(reqs_field),
            "rewards": row.get(rewards_field),
            "refs": dedupe_refs(row.get("refs")),
            "notes": row.get("notes"),
        })
    return rows


def load_card_facts():
    """Structured, prose-free reference blocks minted by the card-facts
    normalizer fan-out (contrib.jsonl kind "card-facts", keyed
    "cardfacts:<card_kind>:<card_id>"). First-seen wins by card_id — the
    ledger is append-only + idempotent, so at most one row per key survives
    a re-run. Returns {card_id: {summary, facts[], req_items[]}}."""
    facts = {}
    for row in read_jsonl(CONTRIB):
        if row.get("kind") != "card-facts":
            continue
        cid = row.get("card_id")
        if not cid or cid in facts:
            continue
        facts[cid] = {
            "summary": row.get("summary") or "",
            "facts": row.get("facts") or [],
            "req_items": row.get("req_items") or [],
        }
    return facts


def attach_card_facts(rows, facts):
    """Fold structured blocks onto each catalog row by id. The render prefers
    facts[]/summary/req_items[] over the prose `notes` blob — notes stays only
    as a fallback for rows the normalizer never reached (e.g. gear unlocks)."""
    hits = 0
    for row in rows:
        block = facts.get(row["id"])
        if not block:
            continue
        row["summary"] = block["summary"]
        row["facts"] = block["facts"]
        row["req_items"] = block["req_items"]
        hits += 1
    return hits


def main():
    rows = load_quest_db() + load_contrib_db()
    card_facts = load_card_facts()
    hits = attach_card_facts(rows, card_facts)
    with open(OUT, "w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False))
            f.write("\n")

    counts = {}
    for row in rows:
        counts[row["kind"]] = counts.get(row["kind"], 0) + 1
    print(f"wrote {len(rows)} rows -> {OUT}")
    for kind in ("quest", "diary", "minigame", "unlock"):
        print(f"  {kind}: {counts.get(kind, 0)}")
    print(f"  card-facts attached: {hits}/{len(rows)} rows structured")


if __name__ == "__main__":
    main()
