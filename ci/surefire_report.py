#!/usr/bin/env python3
"""Summarise Surefire XML test reports, and check the legacy run failed exactly the expected cases.

Usage:
    surefire_report.py summary <reports_dir> <title>
    surefire_report.py check-legacy <reports_dir> <expected_failures_file>

The table is printed, and on GitHub Actions it is also written to the job's summary page.
"""

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# Test method names start with the case ID, for example tc05_retryAfter... -> TC-05.
CASE_ID = re.compile(r"tc-?(\d{2})", re.IGNORECASE)

# Exception class prefixes JUnit puts in front of each assertion message.
EXCEPTION_PREFIX = re.compile(r"^[\w.$]+(?:Error|Exception):\s*")


def broken_rules(element):
    """Return every broken assertion message in a failure, one per line, without exception class names."""
    text = element.get("message") or element.text or ""                    # message attribute, else body
    lines = [line.strip() for line in text.splitlines() if line.strip()]   # drop blank lines
    if len(lines) > 1 and lines[0].endswith("failures)"):                   # assertAll heading, e.g. "TC-05 (3 failures)"
        lines = lines[1:]                                                   # keep the grouped assertions
    return [EXCEPTION_PREFIX.sub("", line)[:220] for line in lines[:12]]   # trimmed, capped for readability


def load_cases(reports_dir):
    """Return one (case_id, test_name, status, details) tuple per test case; details lists broken rules."""
    report_files = sorted(Path(reports_dir).glob("TEST-*.xml"))             # one XML file per test class
    if not report_files:
        sys.exit(f"No Surefire XML reports found in {reports_dir}")         # nothing ran: fail loudly
    cases = []
    for report in report_files:
        for case in ET.parse(report).getroot().iter("testcase"):            # every test method in the class
            name = case.get("name", "")
            match = CASE_ID.search(name)
            case_id = f"TC-{match.group(1)}" if match else name             # fall back to the raw name
            status, details = "PASSED", []
            for tag, label in (("failure", "FAILED"), ("error", "ERROR"), ("skipped", "SKIPPED")):
                element = case.find(tag)
                if element is not None:                                     # first non-pass outcome wins
                    status, details = label, broken_rules(element)
                    break
            cases.append((case_id, name, status, details))
    return sorted(cases)                                                    # TC-01 first


def render(title, cases):
    """Return a Markdown table of the cases."""
    passed = sum(1 for case in cases if case[2] == "PASSED")
    lines = [
        f"### {title}",
        "",
        f"{passed} of {len(cases)} cases passed.",
        "",
        "| Case | Result | First broken rule |",
        "|---|---|---|",
    ]
    for case_id, _name, status, details in cases:
        shown = f"`{details[0]}`" if details else ""                         # code format keeps <...> visible
        lines.append(f"| {case_id} | {status.lower()} | {shown.replace('|', '/')} |")
    return "\n".join(lines) + "\n"


def escape_workflow_data(text):
    """Escape text for a GitHub Actions workflow command, so it survives as one annotation."""
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def annotate(title, cases):
    """On GitHub Actions, attach one line per case to the run as a notice, visible on the run page and the API."""
    if not os.environ.get("GITHUB_ACTIONS"):                                # only inside a GitHub Actions run
        return
    passed = sum(1 for case in cases if case[2] == "PASSED")
    lines = [f"{passed} of {len(cases)} cases passed."]
    for case_id, _name, status, details in cases:
        lines.append(f"{case_id} {status.lower()}")                         # one line per case
        lines.extend(f"    {detail}" for detail in details)                 # then every broken rule, indented
    safe_title = escape_workflow_data(title).replace(":", "%3A").replace(",", "%2C")   # property escaping
    print(f"::notice title={safe_title}::{escape_workflow_data(chr(10).join(lines))}")


def publish(markdown, title=None, cases=None):
    """Print the Markdown and, on GitHub Actions, append it to the job summary and add a results notice."""
    print(markdown)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")                    # set by GitHub Actions
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write(markdown + "\n")
    if title is not None and cases is not None:
        annotate(title, cases)


def check_legacy(reports_dir, expected_file):
    """Fail unless exactly the expected cases failed on assertions, with no errors."""
    cases = load_cases(reports_dir)
    expected = {
        line.split("#", 1)[0].strip()                                       # ignore comments
        for line in Path(expected_file).read_text(encoding="utf-8").splitlines()
    } - {""}                                                                # ignore blank lines
    failed = {case[0] for case in cases if case[2] == "FAILED"}
    errored = {case[0] for case in cases if case[2] == "ERROR"}

    title = "Legacy job: DEF-001 reproduction"
    publish(render(title, cases), title, cases)

    problems = []
    if errored:
        problems.append(f"These cases crashed instead of failing an assertion: {sorted(errored)}")
    if failed - expected:
        problems.append(f"Unexpected failures: {sorted(failed - expected)}")
    if expected - failed:
        problems.append(
            f"Expected to fail but passed, so the suite no longer catches DEF-001: {sorted(expected - failed)}")
    for problem in problems:
        print(f"::error::{problem}")                                        # annotate the GitHub run
    if problems:
        return 1
    print(f"The legacy job failed exactly the expected cases: {', '.join(sorted(expected))}")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    summary = commands.add_parser("summary", help="print a results table")
    summary.add_argument("reports_dir")
    summary.add_argument("title")
    legacy = commands.add_parser("check-legacy", help="verify the legacy job fails exactly the expected cases")
    legacy.add_argument("reports_dir")
    legacy.add_argument("expected_failures_file")
    args = parser.parse_args()

    if args.command == "summary":
        cases = load_cases(args.reports_dir)
        publish(render(args.title, cases), args.title, cases)
        return 0
    return check_legacy(args.reports_dir, args.expected_failures_file)


if __name__ == "__main__":
    sys.exit(main())
