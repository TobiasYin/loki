import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Type } from 'typebox';
import { ThorRuntime, ToolRegistry, type ModelProfile } from '../src/index.js';
import { descriptor, fixture, PNG } from './fixtures.js';

const calls = ['app', 'capture', 'tree'];
function batchSse(protocol: ModelProfile['protocol']): string {
  if (protocol === 'openai-completions') {
    const chunk = (delta: unknown, finish: string | null = null) => `data: ${JSON.stringify({ id: 'batch-response', object: 'chat.completion.chunk', created: 0, model: 'fixture-model', choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`;
    return chunk({ role: 'assistant', tool_calls: calls.map((name, index) => ({ index, id: `call-${name}`, type: 'function', function: { name, arguments: '{}' } })) }) + chunk({}, 'tool_calls') + 'data: [DONE]\n\n';
  }
  const frame = (event: Record<string, unknown>) => `event: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`;
  if (protocol === 'anthropic-messages') {
    const events: Record<string, unknown>[] = [{ type: 'message_start', message: { id: 'batch-response', type: 'message', role: 'assistant', model: 'fixture-model', content: [], stop_reason: null, usage: { input_tokens: 10, output_tokens: 0 } } }];
    calls.forEach((name, index) => events.push({ type: 'content_block_start', index, content_block: { type: 'tool_use', id: `call-${name}`, name, input: {} } }, { type: 'content_block_delta', index, delta: { type: 'input_json_delta', partial_json: '{}' } }, { type: 'content_block_stop', index }));
    events.push({ type: 'message_delta', delta: { stop_reason: 'tool_use' }, usage: { output_tokens: 10 } }, { type: 'message_stop' });
    return events.map(frame).join('');
  }
  const events: Record<string, unknown>[] = [{ type: 'response.created', response: { id: 'batch-response', status: 'in_progress' } }];
  const output = calls.map((name, index) => {
    const item = { type: 'function_call', id: `fc-${name}`, call_id: `call-${name}`, name, arguments: '{}', status: 'completed' };
    events.push({ type: 'response.output_item.added', output_index: index, item: { ...item, arguments: '', status: 'in_progress' } }, { type: 'response.function_call_arguments.delta', output_index: index, item_id: item.id, delta: '{}' }, { type: 'response.output_item.done', output_index: index, item });
    return item;
  });
  events.push({ type: 'response.completed', response: { id: 'batch-response', status: 'completed', output, usage: { input_tokens: 10, output_tokens: 10, total_tokens: 20 } } });
  return events.map(frame).join('');
}
function readRegistry() {
  const result = new ToolRegistry();
  result.register({ packId: 'batch-reads', packVersion: '1', tools: calls.map((name, index) => ({ ...descriptor(name),
    toolId: ['perception.app_info', 'perception.capture_screen', 'perception.ui_tree'][index]!, inputSchema: Type.Object({}, { additionalProperties: false }),
    resources: ['selected-screen/snapshot'],
  })) });
  return result;
}

for (const protocol of ['openai-responses', 'openai-completions', 'anthropic-messages'] as const) {
  test(`${protocol}: fresh read completions promote Native target only after the whole Pi tool batch and queue the new L0 before continuation`, async () => {
    const f = fixture(protocol, [{ call: { name: 'app', args: '{}' } }, { text: 'All three current observations were received.' }]);
    f.snapshot.l0.snapshotId = 'authorized-model-response';
    const fetch = f.ports.network.fetch;
    f.ports.network.fetch = async (...args) => {
      const reply = await fetch(...args);
      if (f.requests.length !== 1) return reply;
      await reply.body?.cancel();
      return new Response(batchSse(protocol), { headers: { 'content-type': 'text/event-stream' } });
    };
    let pendingSnapshot: string | undefined;
    const promotions: { completed: number; target: string }[] = [];
    const read = f.ports.authority.read;
    f.ports.authority.read = async (...args) => {
      // Match ProductHost: fresh read results are pending until a refresh=true
      // boundary; ordinary tool pre/post checks retain their request's source.
      if (args[2] && pendingSnapshot) {
        f.snapshot.l0.snapshotId = pendingSnapshot;
        promotions.push({ completed: f.executions.length, target: pendingSnapshot });
        pendingSnapshot = undefined;
      }
      return read(...args);
    };
    f.setTool(async envelope => {
      const name = envelope.toolId.split('.').at(-1)!;
      pendingSnapshot = `completion-${f.executions.length}`;
      return { content: [{ type: 'text', text: JSON.stringify({ requestSnapshotId: envelope.targetSnapshotId, targetSnapshotId: pendingSnapshot, observed: name }) },
        ...(envelope.toolId === 'perception.capture_screen' ? [{ type: 'attachment' as const, attachmentId: 'tool-image' }] : [])],
        source: 'native-fresh-read', observedAt: Date.now(), truncated: false, outcome: 'observed' };
    });
    const result = await new ThorRuntime(f.ports, readRegistry()).run(f.lease, [{ type: 'text', text: 'Read the app, screenshot and UI tree together.' }]);
    assert.equal(result.state, 'completed');
    assert.deepEqual(f.executions.map(entry => entry.toolId), ['perception.app_info', 'perception.capture_screen', 'perception.ui_tree']);
    assert.ok(f.executions.every(entry => entry.targetSnapshotId === 'authorized-model-response'), 'Every call in the same model response keeps its original authorized source');
    assert.deepEqual(promotions, [{ completed: 3, target: 'completion-3' }]);
    assert.equal(f.requests.length, 2);
    assert.equal(f.events.filter(event => event.type === 'tool_result' && event.data.error).length, 0);
    const body = JSON.stringify(f.requests[1]!.body);
    assert.ok(body.includes(PNG), 'The captured image reaches the immediately following request');
    assert.ok(body.includes('<L0>') && body.includes('completion-3'), 'Pi flushes the latest batch observation before continuation');
    assert.ok(f.contexts[1]!.l0SnapshotId === 'completion-3');
  });
}
