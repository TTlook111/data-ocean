import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.join(root, 'prototypes/flows/问数流程.json');
const graph = JSON.parse(fs.readFileSync(source, 'utf8'));
const data = JSON.parse(fs.readFileSync(path.join(root, 'prototypes/flows/问数节点详情.json'), 'utf8'));
const canonical = path.join(root, graph.meta.output);
const digest = value => crypto.createHash('sha256').update(value).digest('hex');
const archify = process.env.ARCHIFY_BIN || path.join(os.homedir(), '.codex/skills/archify/bin/archify.mjs');
if (!process.argv.includes('--reuse-graph')) {
  const receipt = JSON.parse(execFileSync(process.execPath, [archify, 'deliver', 'architecture', source, canonical, '--quality', 'showcase', '--json'], { cwd: root, encoding: 'utf8' }));
  if (!receipt.ok || receipt.validation.errors || receipt.validation.warnings) throw new Error('Diagram did not pass its artifact checks.');
}
const proof = JSON.parse(fs.readFileSync(canonical.replace(/\.html$/, '.delivery.json'), 'utf8'));
if (proof.status !== 'current' || proof.specification.sha256 !== digest(fs.readFileSync(source)) || proof.artifact.sha256 !== digest(fs.readFileSync(canonical))) throw new Error('Compiled graph does not match current source.');
const byId = new Map(graph.components.map(node => [node.id, node]));
if (data.mainPath.length !== 9 || new Set(data.mainPath).size !== 9) throw new Error('The main path must contain nine execution nodes.');
for (const [index, id] of data.mainPath.entries()) {
  const node = byId.get(id);
  if (!node) throw new Error(`Missing main node ${id}.`);
  if (node.pos[1] !== byId.get(data.mainPath[0]).pos[1]) throw new Error(`Main node ${id} must remain on the same horizontal level.`);
  if (index && node.pos[0] <= byId.get(data.mainPath[index - 1]).pos[0]) throw new Error(`Main node ${id} must follow the left-to-right path.`);
  if (index && !graph.connections.some(edge => edge.from === data.mainPath[index - 1] && edge.to === id)) throw new Error(`Missing main transition into ${id}.`);
}
// 每个触发位置直接进入人工追问，不再由通用错误节点代为分流。
for (const from of ['understand', 'enrich', 'generate', 'semantic', 'security']) {
  const to = 'clarify';
  if (!graph.connections.some(edge => edge.from === from && edge.to === to)) throw new Error(`Missing direct clarification entry from ${from}.`);
}
if (graph.components.filter(node => node.id === 'clarify').length !== 1) throw new Error('The clarification node must appear once.');
if (byId.has('supplement')) throw new Error('Governance knowledge must use one shared enrichment node.');
for (const [from, to] of [['outputRules', 'security'], ['governance', 'enrich'], ['semantic', 'enrich'], ['enrich', 'semantic'], ['security', 'enrich'], ['enrich', 'security']]) if (!graph.connections.some(edge => edge.from === from && edge.to === to)) throw new Error(`Missing shared knowledge loop: ${from} -> ${to}`);
// 本题答复先重解析；新请求和终止是仅有的另外两类出口。
const clarificationTargets = new Set(['understand', 'prepare', 'end']);
for (const edge of graph.connections.filter(edge => edge.from === 'clarify')) if (!clarificationTargets.has(edge.to)) throw new Error(`Clarification must not bypass intent parsing: ${edge.to}.`);
for (const to of clarificationTargets) if (!graph.connections.some(edge => edge.from === 'clarify' && edge.to === to)) throw new Error(`Missing clarification route: ${to}`);
for (const from of ['understand', 'retrieve', 'enrich', 'generate', 'semantic', 'security', 'execute', 'answer', 'repair', 'reconcile', 'clarify']) if (!graph.connections.some(edge => edge.from === from && edge.to === 'end')) throw new Error(`Missing terminal exit: ${from}`);
const repairSources = new Set(['semantic', 'security', 'execute', 'answer', 'reconcile']);
for (const edge of graph.connections.filter(edge => edge.to === 'repair')) if (!repairSources.has(edge.from)) throw new Error(`SQL repair must originate after generation: ${edge.from}.`);
for (const from of repairSources) if (!graph.connections.some(edge => edge.from === from && edge.to === 'repair')) throw new Error(`Missing unified SQL repair entry: ${from}`);
for (const node of data.nodes) {
  if (!byId.has(node.id) || !node.purpose || ['input', 'output', 'routes', 'rules'].some(key => !node[key]?.length)) throw new Error(`Incomplete details: ${node.id}`);
  node.label = byId.get(node.id).label;
}
if (data.nodes.length !== graph.components.length) throw new Error('Every node must have details.');
const native = fs.readFileSync(canonical, 'utf8');
const extension = fs.readFileSync(path.join(root, 'prototypes/flows/问数流程.template.html'), 'utf8').replace('{{QUERY_DATA}}', () => JSON.stringify(data).replaceAll('<', '\\u003c'));
const output = native.replace('</body>', `${extension}\n</body>`);
if (output.includes('{{QUERY_')) throw new Error('Unresolved template markers.');
const outputPath = path.join(root, 'prototypes/flows/问数流程.html'); fs.writeFileSync(outputPath, output, 'utf8');
fs.writeFileSync(path.join(path.dirname(canonical), 'page-build.json'), `${JSON.stringify({ graph: digest(native), page: digest(output), mainNodes: data.mainPath, detailPanels: data.nodes.length, viewer: 'native-with-node-details' }, null, 2)}\n`);
console.log(JSON.stringify({ output: outputPath, mainNodes: 9, detailPanels: data.nodes.length, viewer: 'native-with-node-details' }));
