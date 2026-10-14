import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const workflow = readFileSync(new URL('../workflows/autoassign.yml', import.meta.url), 'utf8');
const script = workflow.split('          script: |\n')[1]?.replace(/^            /gm, '');
assert.ok(script, 'Autoassign workflow must contain its assignment script');
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const assign = new AsyncFunction('context', 'github', script);

for (const type of ['issue', 'pull_request']) {
  for (const alreadyAssigned of [false, true]) {
    const calls = [];
    const context = {
      repo: { owner: 'dutixlf', repo: 'echo-combine-extension' },
      payload: {
        [type]: { number: 42, assignees: alreadyAssigned ? [{ login: 'someone-else' }] : [] },
      },
    };
    const github = { rest: { issues: { addAssignees: async (request) => calls.push(request) } } };
    await assign(context, github);
    assert.deepEqual(calls, alreadyAssigned ? [] : [{
      owner: 'dutixlf',
      repo: 'echo-combine-extension',
      issue_number: 42,
      assignees: ['dutixlf'],
    }]);
    console.log(`PASS: ${type}, ${alreadyAssigned ? 'preserved existing assignment' : 'assigned to dutixlf'}`);
  }
}
