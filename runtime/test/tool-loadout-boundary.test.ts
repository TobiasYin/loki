import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Type } from 'typebox';
import { ThorRuntime, ToolRegistry, type ModelProfile, type ToolDescriptor } from '../src/index.js';
import { descriptor, fixture } from './fixtures.js';

function loadout() {
  const registry = new ToolRegistry();
  const tap: ToolDescriptor = { ...descriptor('input_tap', ['screen_control']), toolId: 'input.tap', effectClass: 'input', executor: 'native-device',
    platformPrerequisites: ['input.tap'], resources: ['selected-screen/snapshot'], inputSchema: Type.Object({}, { additionalProperties: false }) };
  const capture: ToolDescriptor = { ...descriptor('perception_capture', ['screen_perception']), toolId: 'perception.capture_screen', executor: 'native-device',
    platformPrerequisites: ['perception.capture_screen'], resources: ['selected-screen/snapshot'], inputSchema: Type.Object({}, { additionalProperties: false }) };
  registry.register({ packId: 'native-loadout-fixture', packVersion: '1', tools: [tap, capture] });
  return registry;
}
function wireTools(protocol: ModelProfile['protocol'], body: any): string[] {
  return (body.tools ?? []).map((tool: any) => protocol === 'openai-completions' ? tool.function.name : tool.name).sort();
}
for (const protocol of ['openai-responses', 'openai-completions', 'anthropic-messages'] as const) {
  for (const when of ['before-first-request', 'initial-snapshot'] as const) {
    test(`${protocol} ${when}: a stale target stays a declared Pi tool and returns Native recovery instead of tool-not-found`, async () => {
      const f = fixture(protocol, [
        { call: { name: 'input_tap', id: 'stale-attempt', args: '{}' } },
        { call: { name: 'perception_capture', id: 'fresh-observation', args: '{}' } },
        { call: { name: 'input_tap', id: 'new-action', args: '{}' } },
        { text: 'Observed the current screen and chose a new action.' },
      ]);
      f.snapshot.session.enabled.screen_control = true;
      f.snapshot.availability['input.tap'] = { status: 'ready' };
      f.snapshot.availability['perception.capture_screen'] = { status: 'ready' };
      const stale = () => { f.snapshot.availability['input.tap'] = { status: 'temporarily_unavailable', reasonCode: 'TARGET_CHANGED', reason: 'Observe the current selected screen' }; };
      if (when === 'initial-snapshot') stale();
      const read = f.ports.authority.read;
      let requestBoundaryRead = false;
      f.ports.authority.read = async (...args) => {
        // Reproduce Native changing its availability after AgentSession has
        // projected declarations but before our prepareRequest observer runs.
        if (when === 'before-first-request' && args[2] && !requestBoundaryRead) { requestBoundaryRead = true; stale(); }
        return read(...args);
      };
      let effects = 0;
      f.setTool(async envelope => {
        if (envelope.toolId === 'perception.capture_screen') {
          f.snapshot.availability['input.tap'] = { status: 'ready' };
          return { content: [{ type: 'text', text: 'CONFIRMED_FRESH_SCREEN' }], source: 'native-fixture', observedAt: Date.now(), truncated: false, outcome: 'observed' };
        }
        effects++;
        return { content: [{ type: 'text', text: 'NEW_ACTION_SUBMITTED' }], source: 'native-fixture', observedAt: Date.now(), truncated: false, outcome: 'submitted' };
      });
      const result = await new ThorRuntime(f.ports, loadout()).run(f.lease, [{ type: 'text', text: 'Observe again if the input target is stale, then choose a new action.' }]);
      assert.equal(result.state, 'completed'); assert.equal(effects, 1);
      assert.deepEqual(f.executions.map(entry => entry.toolId), ['perception.capture_screen', 'input.tap']);
      const refusal = f.events.find(event => event.type === 'tool_result' && event.data.error === 'TARGET_CHANGED');
      assert.ok(refusal, 'The registered Native wrapper returns the fixed actionable rejection');
      assert.equal((refusal.data.recovery as any).submission, 'not_dispatched');
      assert.equal((refusal.data.recovery as any).continueRun, true);
      assert.equal((refusal.data.recovery as any).retryPreviousAction, false);
      assert.equal(refusal.data.preflightDiagnostic, 'availability_target_changed');
      assert.equal((refusal.data.recovery as any).preflightDiagnostic, 'availability_target_changed');
      assert.equal(f.events.filter(event => event.type === 'tool_started' && event.data.toolId === 'input.tap').length, 1);
      assert.equal(f.requests.length, 4); assert.equal(f.contexts.length, 4);
      for (let index = 0; index < f.requests.length; index++) {
        const declared = wireTools(protocol, f.requests[index]!.body);
        assert.deepEqual(declared, ['input_tap', 'perception_capture']);
        assert.deepEqual(f.contexts[index]!.effectiveTools.slice().sort(), ['input.tap', 'perception.capture_screen'], 'Manifest records the exact loadout Pi sent');
      }
      assert.match(JSON.stringify(f.requests[1]!.body), /observe_again/);
      assert.match(JSON.stringify(f.requests[2]!.body), /CONFIRMED_FRESH_SCREEN/);
      assert.doesNotMatch(JSON.stringify(f.turns), /Tool input_tap not found/);
    });
  }
}

for (const protocol of ['openai-responses', 'openai-completions', 'anthropic-messages'] as const) {
  test(`${protocol}: a Native-issued schema stays bound to Pi declarations while later authority probes change execution availability`, async () => {
    const f = fixture(protocol, [
      { call: { name: 'input_tap', id: 'backend-rejected', args: '{}' } },
      { call: { name: 'perception_capture', id: 'observe-backend', args: '{}' } },
      { call: { name: 'input_tap', id: 'new-action', args: '{}' } },
      { text: 'Observed recovery before choosing the new action.' },
    ]);
    f.snapshot.session.enabled.screen_control = true;
    f.snapshot.availability['input.tap'] = { status: 'ready' };
    f.snapshot.availability['perception.capture_screen'] = { status: 'ready' };
    f.snapshot.toolSchemaId = 'schema-initial';
    const issued = new Map([
      ['schema-initial', ['input.tap', 'perception.capture_screen']],
      ['schema-backend-missing', ['perception.capture_screen']],
      ['schema-restored', ['input.tap', 'perception.capture_screen']],
    ]);
    const read = f.ports.authority.read;
    let firstRefresh = true;
    f.ports.authority.read = async (...args) => {
      if (args[2] && firstRefresh) {
        firstRefresh = false;
        f.snapshot.availability['input.tap'] = { status: 'backend_missing' };
        f.snapshot.toolSchemaId = 'schema-backend-missing';
      }
      return read(...args);
    };
    const save = f.ports.persistence.saveContext;
    f.ports.persistence.saveContext = async manifest => {
      // Native checks the immutable issued schema, not the dynamic ready set.
      assert.deepEqual(manifest.effectiveTools.slice().sort(), issued.get(manifest.toolSchemaId!));
      return save(manifest);
    };
    let effects = 0;
    f.setTool(async envelope => {
      if (envelope.toolId === 'perception.capture_screen') {
        f.snapshot.availability['input.tap'] = { status: 'ready' };
        f.snapshot.toolSchemaId = 'schema-restored';
      } else effects++;
      return { content: [{ type: 'text', text: 'CURRENT_CHECKED_RESULT' }], source: 'native-fixture', observedAt: Date.now(), truncated: false, outcome: envelope.toolId === 'input.tap' ? 'submitted' : 'observed' };
    });
    assert.equal((await new ThorRuntime(f.ports, loadout()).run(f.lease, [{ type: 'text', text: 'Observe current availability before acting.' }])).state, 'completed');
    assert.equal(effects, 1);
    assert.deepEqual(f.executions.map(e => e.toolId), ['perception.capture_screen', 'input.tap']);
    assert.equal(f.events.filter(e => e.type === 'tool_result' && e.data.error === 'BACKEND_UNAVAILABLE').length, 1);
    assert.deepEqual(f.contexts.map(c => c.toolSchemaId), ['schema-initial', 'schema-backend-missing', 'schema-restored', 'schema-restored']);
    for (let index = 0; index < f.requests.length; index++) {
      assert.deepEqual(wireTools(protocol, f.requests[index]!.body), f.contexts[index]!.effectiveTools.map(id => id === 'input.tap' ? 'input_tap' : 'perception_capture').sort());
    }
    assert.doesNotMatch(JSON.stringify(f.requests.map(r => r.body)), /schema-initial|schema-backend-missing|schema-restored/, 'Transport schema identifiers never enter Pi messages or provider prompt prefixes');
  });

  test(`${protocol}: genuine backend removal and restoration update executable tools and Pi wire declarations at the same boundary`, async () => {
    const f = fixture(protocol, [
      { call: { name: 'perception_capture', id: 'backend-disappears', args: '{}' } },
      { call: { name: 'perception_capture', id: 'backend-restores', args: '{}' } },
      { call: { name: 'input_tap', id: 'authorized-new-action', args: '{}' } },
      { text: 'The backend recovered and the new action completed.' },
    ]);
    f.snapshot.session.enabled.screen_control = true;
    f.snapshot.availability['input.tap'] = { status: 'ready' };
    f.snapshot.availability['perception.capture_screen'] = { status: 'ready' };
    let captures = 0, effects = 0;
    f.setTool(async envelope => {
      if (envelope.toolId === 'perception.capture_screen') {
        captures++;
        f.snapshot.availability['input.tap'] = captures === 1 ? { status: 'backend_missing', reason: 'Native input backend disconnected' } : { status: 'ready' };
        return { content: [{ type: 'text', text: 'OBSERVED_CURRENT_SCREEN' }], source: 'native-fixture', observedAt: Date.now(), truncated: false, outcome: 'observed' };
      }
      effects++;
      return { content: [{ type: 'text', text: 'NEW_ACTION_SUBMITTED' }], source: 'native-fixture', observedAt: Date.now(), truncated: false, outcome: 'submitted' };
    });
    assert.equal((await new ThorRuntime(f.ports, loadout()).run(f.lease, [{ type: 'text', text: 'Wait for the input backend to recover before a new action.' }])).state, 'completed');
    assert.equal(effects, 1); assert.equal(captures, 2); assert.equal(f.requests.length, 4);
    const expected = [['input_tap', 'perception_capture'], ['perception_capture'], ['input_tap', 'perception_capture'], ['input_tap', 'perception_capture']];
    for (let index = 0; index < expected.length; index++) {
      assert.deepEqual(wireTools(protocol, f.requests[index]!.body), expected[index]);
      const manifestNames = f.contexts[index]!.effectiveTools.map(id => id === 'input.tap' ? 'input_tap' : 'perception_capture').sort();
      assert.deepEqual(manifestNames, expected[index], 'Pi declarations, implementations and persisted manifest change together');
    }
    assert.equal(f.events.filter(event => event.type === 'tool_result' && event.data.error).length, 0);
  });
}
