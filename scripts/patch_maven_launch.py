from pathlib import Path
import re

path = Path("run_experiment.py")
source = path.read_text(encoding="utf-8-sig")

pattern = re.compile(
    r'''            command = \(
                'mvn exec:java '
                '"-Dexec\.mainClass=research\.CloudSimValidation" '
                f'"-Dexec\.args=\{workload_path\} \{cloudsim_path\} \{workers\}"'
            \)

            run_command\(\["cmd\.exe", "/c", command\], ROOT, env\)'''
)

replacement = '''            command = (
                "& mvn exec:java "
                "'-Dexec.mainClass=research.CloudSimValidation' "
                f"'-Dexec.args={workload_path} {cloudsim_path} {workers}'"
            )

            run_command(
                ["powershell.exe", "-NoProfile", "-Command", command],
                ROOT,
                env,
            )'''

updated, count = pattern.subn(lambda _: replacement, source)

if count != 1:
    raise SystemExit(
        f"PATCH FAILED: expected one Maven launch block, found {count}. "
        "Original file was not changed."
    )

path.write_text(updated, encoding="utf-8")
print("PATCH SUCCESSFUL: Maven launch now uses PowerShell.")
