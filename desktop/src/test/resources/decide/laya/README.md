Referenzwerte aus dem Laya-Projekt (https://github.com/NandhaKishorM/laya, Apache-2.0),
Commit c73908d96ff73c0062c340a11bf7c54ec7d8bc4d, Verzeichnis `laya-java/fixtures`:

* `tokenizer_ids.json` – Token-IDs der Hugging-Face-Tokenizer für einen Testkorpus
* `predict.json` – Vorhersagen des Checkpoints `laya-multilingual` (ONNX-Export, fp32)

Die Paritätstests (`LayaParityTest`) laufen nur, wenn die Modelle unter `~/.devtools-mcp/models` liegen.
