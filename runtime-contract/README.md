The native base and feature must retain these resource IDs. `resource-ids-v1.txt` was
exported with `scripts/export_runtime_resource_ids.py` from the original signed public
5.5.499.1326 debug APK, SHA-256
`029ff72e3df9a97a161e501991eb92037b2e5395d68f45774618eae150e79564`.
Only its package prefix is normalized. The 13,441 symbol values are unchanged.
Build variants rewrite the package prefix, never the numeric IDs. A new base generation
must export its complete map before independently publishing later feature packages.
