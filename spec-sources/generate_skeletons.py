#!/usr/bin/env python3
"""Create the Spock spec classes for a requirements catalog: one feature per requirement.

Usage: python3 -I generate_skeletons.py [RFC number, default 7643]

Reads rfcNNNN-requirements.yaml and writes, into src/main/groovy/de/palsoftware/scim/validator/specs/,
one class per (RFC, area), for example Rfc7643AttributeModelSpec, with one feature per requirement
whose disposition is not "na":

    @Ignore("not implemented")
    @SpecRef("RFC7643-2.3.6-1a2b3c")
    @RequiresCapability("bulk")                      (only if the capability is not core)
    def "RFC7643-2.3.6-1a2b3c: <label>"() { expect: assert false : "not implemented" }

with the section, lines, level and the full quote as comments above the feature. The skeleton is
@Ignore'd, so it reports as skipped until someone writes the body and removes the @Ignore.

The files are hand-edited from then on, so the generator never rewrites anything: for a class that
exists it only appends skeletons for requirements that have no feature yet. It never removes
anything either; check_spec_coverage.py reports tags that no longer match the catalog.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rfc_config as cfg  # noqa: E402
from check_requirements import parse  # noqa: E402

RFC = cfg.rfc_arg(sys.argv)
SPECS = os.path.join(cfg.HERE, "..", "src", "main", "groovy", "de", "palsoftware", "scim", "validator", "specs")
AREA_TITLE = {"general": "General", "attribute-model": "Attribute model", "common-attributes": "Common attributes and extensions",
              "user-group-schemas": "User, Group and Enterprise User schemas", "service-provider-config": "ServiceProviderConfig",
              "resource-type": "ResourceType", "schema-definition": "Schema definition", "auth": "Authentication and authorization",
              "resources": "Resource lifecycle", "query": "Querying resources", "filtering": "Filtering",
              "sorting-pagination": "Sorting and pagination", "patch": "PATCH", "bulk": "Bulk", "me": "/Me endpoint",
              "formats": "Data formats and notation", "errors": "HTTP status and error handling",
              "versioning": "Protocol and resource versioning", "multi-tenancy": "Multi-tenancy",
              "security": "Security considerations", "registry": "IANA registration",
              "cursor-pagination": "Cursor-based pagination", "events": "SCIM events", "async": "Asynchronous requests"}

HEADER = """package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import spock.lang.Ignore

/**
 * RFC {rfc}: {title}
 *
 * One feature per requirement of requirements-rfc{rfc}.yaml (area "{area}"). Features start as
 * skeletons: {at}Ignore'd until the body is written. Do not rename the features or change the
 * {at}SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class {cls} extends ScimBaseSpec {{
"""


def camel(area):
    return "".join(p.capitalize() for p in area.split("-"))


def label(e):
    q = re.sub(r"\s+", " ", e["quote"]).strip()
    text = f"{e['subject']}: {q}" if e.get("subject") else q
    if len(text) > 110:
        text = text[:107].rsplit(" ", 1)[0] + "..."
    return re.sub(r'["$\\]', "'", text)


def feature(e, sec_titles):
    lines = e["lines"]
    out = [f"    // RFC {e['rfc']} section {e['section']} ({sec_titles.get(e['section'], '')}), "
           f"{e['level']}{' NOT' if e['polarity'] == 'negative' else ''}, lines {lines[0]}-{lines[1]}",
           f"    // {re.sub(chr(10), ' ', e['quote'])}",
           '    @Ignore("not implemented")',
           f'    @SpecRef("{e["id"]}")']
    if e["capability"] != "core":
        out.append(f'    @RequiresCapability("{e["capability"]}")')
    out += [f'    def "{e["id"]}: {label(e)}"() {{', "        expect:", '        assert false : "not implemented"', "    }"]
    return "\n".join(out)


def sync_capabilities(text, entries):
    """Make @RequiresCapability of existing features equal the catalog's capability (a rule may have changed)."""
    fixed = 0
    for e in entries:
        pat = re.compile(r'(    @SpecRef\("' + re.escape(e["id"]) + r'"\)\n)(    @RequiresCapability\("[^"]*"\)\n)?')
        want = "" if e["capability"] == "core" else f'    @RequiresCapability("{e["capability"]}")\n'
        m = pat.search(text)
        if m and (m.group(2) or "") != want:
            text = text[:m.start()] + m.group(1) + want + text[m.end():]
            fixed += 1
    return text, fixed


def section_titles(rfc):
    import json
    return {r["section"]: r["section_title"] for r in json.load(open(cfg.paths(rfc)["normative"], encoding="utf-8"))}


def main():
    entries = parse(cfg.catalog_path(RFC))
    titles = section_titles(RFC)
    rfc_title = {"7643": "SCIM Core Schema", "7644": "SCIM Protocol", "9865": "SCIM Cursor-Based Pagination",
                 "9967": "SCIM Profile for Security Event Tokens"}[RFC]
    by_area = {}
    for e in entries:
        if e["disposition"] != "na":
            by_area.setdefault(e["area"], []).append(e)
    os.makedirs(SPECS, exist_ok=True)
    created = appended = 0
    for area, es in sorted(by_area.items()):
        cls = f"Rfc{RFC}{camel(area)}Spec"
        path = os.path.join(SPECS, cls + ".groovy")
        if os.path.exists(path):
            text = open(path, encoding="utf-8").read()
            text, fixed = sync_capabilities(text, es)
            if fixed:
                open(path, "w", encoding="utf-8").write(text)
                print(f"  {cls}: corrected @RequiresCapability on {fixed} feature(s)")
            have = set(re.findall(r'@SpecRef\("([^"]+)"\)', text))
            missing = [e for e in es if e["id"] not in have]
            if missing:
                body = "\n\n".join(feature(e, titles) for e in missing)
                i = text.rstrip().rfind("}")
                open(path, "w", encoding="utf-8").write(text[:i].rstrip() + "\n\n" + body + "\n}\n")
                appended += len(missing)
                print(f"  {cls}: appended {len(missing)}")
        else:
            head = HEADER.format(rfc=RFC, title=rfc_title, area=AREA_TITLE[area], cls=cls, at="@")
            open(path, "w", encoding="utf-8").write(head + "\n" + "\n\n".join(feature(e, titles) for e in es) + "\n}\n")
            created += 1
            print(f"  {cls}: created with {len(es)} features")
    print(f"RFC {RFC}: {created} class(es) created, {appended} feature(s) appended")


if __name__ == "__main__":
    main()
