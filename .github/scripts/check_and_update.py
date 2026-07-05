import os
import re
import urllib.request
import json
import tomllib
import sys
import subprocess

# Paths
ROOT_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TOML_PATH = os.path.join(ROOT_DIR, "gradle", "libs.versions.toml")
REPORT_PATH = os.path.join(ROOT_DIR, "build", "dependencyUpdates", "report.json")

# Excluded catalog version references (custom forks, managed by triggers)
EXCLUDE_REFS = {"lavaplayer", "youtubeSource"}

def find_ref_for_library(group, name, toml_data):
    for lib_key, lib_val in toml_data.get("libraries", {}).items():
        if isinstance(lib_val, dict):
            lib_group = lib_val.get("group")
            lib_name = lib_val.get("name")
            if not lib_group or not lib_name:
                module = lib_val.get("module")
                if module and ":" in module:
                    lib_group, lib_name = module.split(":", 1)
            
            if lib_group == group and lib_name == name:
                version = lib_val.get("version")
                if isinstance(version, dict) and "ref" in version:
                    return version["ref"]
    return None

def find_ref_for_plugin(plugin_id, toml_data):
    for plugin_key, plugin_val in toml_data.get("plugins", {}).items():
        if isinstance(plugin_val, dict):
            if plugin_val.get("id") == plugin_id:
                version = plugin_val.get("version")
                if isinstance(version, dict) and "ref" in version:
                    return version["ref"]
    return None

def main():
    if not os.path.exists(TOML_PATH):
        print(f"Error: libs.versions.toml not found at {TOML_PATH}", file=sys.stderr)
        sys.exit(1)

    with open(TOML_PATH, "rb") as f:
        toml_data = tomllib.load(f)

    current_versions = toml_data.get("versions", {})
    
    updates = {}
    changelog_entries = []
    gradle_updated = False

    # Check dependencyUpdates report.json
    if os.path.exists(REPORT_PATH):
        print("Reading Gradle dependency report...")
        with open(REPORT_PATH, "r", encoding="utf-8") as f:
            report = json.load(f)

        # Libraries
        for dep in report.get("outdated", {}).get("dependencies", []):
            group = dep.get("group")
            name = dep.get("name")
            current_val = dep.get("version")
            available = dep.get("available", {})
            latest = available.get("release") or available.get("milestone")
            
            if latest and latest != current_val:
                ref = find_ref_for_library(group, name, toml_data)
                if ref and ref not in EXCLUDE_REFS:
                    updates[ref] = latest
                    changelog_entries.append(f"Updated library `{group}:{name}` to `{latest}` (was `{current_val}`)")
                    gradle_updated = True

        # Plugins
        for dep in report.get("outdated", {}).get("dependencies", []):
            group = dep.get("group")
            name = dep.get("name")
            current_val = dep.get("version")
            available = dep.get("available", {})
            latest = available.get("release") or available.get("milestone")
            
            if name.endswith(".gradle.plugin"):
                plugin_id = name[:-14]
                ref = find_ref_for_plugin(plugin_id, toml_data)
                if ref and ref not in EXCLUDE_REFS:
                    updates[ref] = latest
                    changelog_entries.append(f"Updated plugin `{plugin_id}` to `{latest}` (was `{current_val}`)")
                    gradle_updated = True

        # Gradle Wrapper update checking
        gradle_info = report.get("gradle", {})
        if gradle_info.get("current", {}).get("isUpdateAvailable"):
            latest_gradle = gradle_info["current"]["version"]
            running_gradle = gradle_info["running"]["version"]
            print(f"Gradle wrapper update available: {running_gradle} -> {latest_gradle}")
            updates["gradle-wrapper"] = latest_gradle
            changelog_entries.append(f"Updated Gradle Wrapper to `{latest_gradle}` (was `{running_gradle}`)")
            gradle_updated = True
    else:
        print("Warning: report.json not found. Run ./gradlew dependencyUpdates first.", file=sys.stderr)

    # Apply updates to libs.versions.toml
    toml_updated = False
    if updates:
        toml_updates = {k: v for k, v in updates.items() if k != "gradle-wrapper"}
        if toml_updates:
            with open(TOML_PATH, "r", encoding="utf-8") as f:
                lines = f.readlines()

            version_pattern = re.compile(r'^(\s*([a-zA-Z0-9_\-]+)\s*=\s*")([^"]+)(".*)$')
            updated_lines = []
            for line in lines:
                match = version_pattern.match(line)
                if match:
                    full_prefix, key, current_val, suffix = match.groups()
                    if key in toml_updates:
                        new_val = toml_updates[key]
                        line = f'{full_prefix}{new_val}{suffix}\n'
                        toml_updated = True
                updated_lines.append(line)

            if toml_updated:
                with open(TOML_PATH, "w", encoding="utf-8") as f:
                    f.writelines(updated_lines)
                print("Successfully updated libs.versions.toml.")

        # Update Gradle wrapper if needed
        if "gradle-wrapper" in updates:
            new_gradle = updates["gradle-wrapper"]
            print(f"Running wrapper update command to version {new_gradle}...")
            if os.name == "nt":
                cmd = "gradlew.bat wrapper --gradle-version " + new_gradle + " --no-daemon"
                shell = True
            else:
                cmd = ["./gradlew", "wrapper", "--gradle-version", new_gradle, "--no-daemon"]
                shell = False
            try:
                subprocess.check_call(cmd, shell=shell)
                print("Successfully updated Gradle Wrapper.")
            except Exception as e:
                print(f"Error updating Gradle Wrapper: {e}", file=sys.stderr)

    updated_flag = "true" if (toml_updated or "gradle-wrapper" in updates) else "false"

    print(f"\nSummary of updates (Updated: {updated_flag}):")
    for entry in changelog_entries:
        print(f"- {entry}")

    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a") as f:
            f.write(f"updated={updated_flag}\n")
            f.write(f"gradle_updated={'true' if gradle_updated else 'false'}\n")
            
        github_summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if github_summary:
            with open(github_summary, "w") as f:
                f.write("### Dependency Scan Results\n")
                if changelog_entries:
                    f.write("**Status: Found updates!**\n\n")
                    for entry in changelog_entries:
                        f.write(f"- {entry}\n")
                else:
                    f.write("**Status: All dependencies are up to date!**\n")

    # Save details to build for release phase ingestion
    build_dir = os.path.join(ROOT_DIR, "build")
    os.makedirs(build_dir, exist_ok=True)
    with open(os.path.join(build_dir, "dependency_changes.json"), "w") as f:
        json.dump({
            "updated": updated_flag == "true",
            "gradle_updated": gradle_updated,
            "changes": changelog_entries
        }, f, indent=2)

if __name__ == "__main__":
    main()
