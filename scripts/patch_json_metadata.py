from pathlib import Path

p = Path("src/main/java/research/CloudSimValidation.java")
s = p.read_text(encoding="utf-8")

old = '''        json.append("    \\"execution_model\\": \\"one_job_at_a_time_per_worker\\",\\n");
        json.append("    \\"vm_mips_per_pe\\": ").append(VM_MIPS).append(",\\n");
'''

new = '''        json.append("    \\"execution_model\\": \\"one_job_at_a_time_per_worker\\",\\n");
        json.append("    \\"worker_count\\": ").append(broker.getWorkerCount()).append(",\\n");
        json.append("    \\"vm_mips_per_pe\\": ").append(VM_MIPS).append(",\\n");
'''

if s.count(old) != 1:
    raise RuntimeError("JSON insertion point did not match exactly once. No changes written.")

p.write_text(s.replace(old, new, 1), encoding="utf-8")
print("PATCHED: JSON worker_count metadata")
