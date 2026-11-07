"""Shared settings for the spec-sources scripts: file names per RFC and per-RFC parsing options."""
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))

_MONTH = "(?:January|February|March|April|May|June|July|August|September|October|November|December)"
# Running page footer ("Hunt, et al.   Standards Track   [Page 35]") and running header
# ("RFC 7643    SCIM Core Schema    September 2015"). Works for any RFC in the plain-text format.
HEADER_RE = re.compile(r"^(?:\S.*\[Page \d+\]|RFC \d+\s{2,}\S.*\s" + _MONTH + r"\s\d{4})\s*$")

# Parsing options that differ between RFCs. Anything not listed falls back to DEFAULT.
DEFAULT = {
    # Sections whose paragraphs define attributes with label lines (see extract_normative.label_paths).
    "label_sections": None,
    # Sections holding JSON schema examples whose "description" strings are extracted separately,
    # and the mapping of the example's root name to the prose sections that state its rules.
    "json_example_prefix": None,
    "root_sections": {},
    # Keyword sentences in these sections (by prefix) are descriptive text, not requirements.
    "informative_prefix": None,
    # A sentence in section 1.1 starting with this is a document-wide convention.
    "convention_prefix": None,
}

CONFIG = {
    "7643": {
        "label_sections": re.compile(r"^(2\.4|3\.1|4(\.\d+)*|5|6|7)$"),
        "json_example_prefix": "8.7",
        "root_sections": {
            "User": ("2.4", "3.1", "4.1.1", "4.1.2"),
            "EnterpriseUser": ("4.3",),
            "Group": ("4.2",),
            "ResourceType": ("6",),
            "Schema": ("7",),
            "ServiceProviderConfig": ("5",),
        },
        "informative_prefix": "8.",
        "convention_prefix": "When using these values",
    },
}


def config(rfc):
    return {**DEFAULT, **CONFIG.get(rfc, {})}


def paths(rfc):
    """File names (absolute) for one RFC number given as a string, e.g. '7643'."""
    def p(name):
        return os.path.join(HERE, name)
    return {
        "text": p(f"rfc{rfc}.txt"),
        "errata": p(f"rfc{rfc}-errata.json"),
        "fixed": p(f"rfc{rfc}-fixed.txt"),
        "applied": p(f"errata-applied-rfc{rfc}.json"),
        "report": p(f"errata-report-rfc{rfc}.md"),
        "normative": p(f"normative-rfc{rfc}.json"),
        "unattached": p(f"unattached-json-examples-rfc{rfc}.json"),
    }


def rfc_arg(argv):
    """The RFC number: first argument made only of digits (default 7643)."""
    for a in argv:
        if a.isdigit():
            return a
    return "7643"


# Capability of a requirement = the server feature a test needs (vocabulary of the validator UI:
# core, patch, filter, sort, etag, bulk, search, changePassword, enterprise, cursorPagination,
# async). Assigned by the longest section prefix that matches; an approximate grouping, not a
# judgment of each sentence. `core` = needs no optional server feature.
CAPABILITIES = ["core", "patch", "filter", "sort", "etag", "bulk", "search", "changePassword",
                "enterprise", "cursorPagination", "async"]
CAPABILITY_RULES = {
    "7643": {"4.3": "enterprise"},
    "7644": {"3.4.2.2": "filter", "3.4.2.3": "sort", "3.4.3": "search", "3.5.2": "patch",
             "3.7": "bulk", "3.14": "etag"},
    "9865": {"2": "cursorPagination", "4": "cursorPagination", "5": "cursorPagination"},
    "9967": {"2": "async", "3": "async", "4": "async"},
}
DISPOSITIONS = ["unassigned", "tested", "optional", "na", "gap"]
NA_REASONS = ["client-side", "not-observable", "process", "deployment"]  # required when disposition is na


# Records about one subject, whatever their section: (rfc, subject prefix) -> capability. The password
# attribute of the User resource (RFC 7643 4.1.1) is the behaviour gated by changePassword.supported.
CAPABILITY_BY_SUBJECT = {("7643", "password"): "changePassword"}


def capability_of(rfc, section, subject=""):
    for (r, prefix), cap in CAPABILITY_BY_SUBJECT.items():
        if r == rfc and subject and (subject == prefix or subject.startswith(prefix + ".")):
            return cap
    best = ""
    for prefix in CAPABILITY_RULES.get(rfc, {}):
        if (section == prefix or section.startswith(prefix + ".")) and len(prefix) > len(best):
            best = prefix
    return CAPABILITY_RULES.get(rfc, {}).get(best, "core")


# Area = what a requirement is about, used to group tests into spec classes. Assigned by the longest
# section prefix that matches; sections without a rule get the RFC's default area. Like the
# capability rules this is a grouping by position in the RFC, not a judgment of each sentence.
AREAS = ["general", "attribute-model", "common-attributes", "user-group-schemas", "service-provider-config",
         "resource-type", "schema-definition", "auth", "resources", "query", "filtering", "sorting-pagination",
         "patch", "bulk", "me", "formats", "errors", "versioning", "multi-tenancy", "security", "registry",
         "cursor-pagination", "events", "async"]
AREA_DEFAULT = {"7643": "general", "7644": "general", "9865": "cursor-pagination", "9967": "events"}
AREA_RULES = {
    "7643": {"2": "attribute-model", "3": "common-attributes", "4": "user-group-schemas",
             "5": "service-provider-config", "6": "resource-type", "7": "schema-definition",
             "9": "security", "10": "registry"},
    "7644": {"2": "auth", "3": "resources", "3.1": "general", "3.4": "query", "3.4.2.2": "filtering",
             "3.4.2.3": "sorting-pagination", "3.4.2.4": "sorting-pagination", "3.5.2": "patch",
             "3.7": "bulk", "3.8": "formats", "3.9": "formats", "3.10": "formats", "3.11": "me",
             "3.12": "errors", "3.13": "versioning", "3.14": "versioning", "4": "service-provider-config",
             "6": "multi-tenancy", "7": "security", "8": "registry"},
    "9865": {"4": "service-provider-config", "5": "security"},
    "9967": {"2.5": "async", "3": "async", "4": "service-provider-config", "5": "security", "7": "registry"},
}


def area_of(rfc, section):
    best, rules = "", AREA_RULES.get(rfc, {})
    for prefix in rules:
        if (section == prefix or section.startswith(prefix + ".")) and len(prefix) > len(best):
            best = prefix
    return rules.get(best, AREA_DEFAULT[rfc])


def catalog_path(rfc):
    """The generated requirements catalog, shipped in the jar (the validator UI reads it from the classpath)."""
    return os.path.join(HERE, "..", "src", "main", "resources", "spec", f"rfc{rfc}-requirements.yaml")
