#!/usr/bin/env node
// Remove a sessão/CSRF vivos que o k6 grava em `setup_data` dentro do
// --summary-export (o retorno de setup() vai inteiro para o resumo). Rode
// depois de toda execução cujo resultado for versionado — ver loadtest/README.md.
const fs = require('fs');

const file = process.argv[2];
if (!file) {
  console.error('Uso: node loadtest/scrub-summary.js <arquivo.json>');
  process.exit(1);
}

const data = JSON.parse(fs.readFileSync(file, 'utf8'));
if (data.setup_data) {
  data.setup_data = {
    runId: data.setup_data.runId,
    sceneCount: (data.setup_data.sceneIds || []).length,
  };
}
fs.writeFileSync(file, JSON.stringify(data, null, 2) + '\n');
console.log(`setup_data sanitizado em ${file}`);
