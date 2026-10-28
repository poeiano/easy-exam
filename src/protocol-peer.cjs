// Native protocol reference client, used by the desktop smoke check.
const srp = require('secure-remote-password/client');
const { randomBytes } = require('node:crypto');
const { binding, equal } = require('./relay.cjs');
function createPeer(code = '0042') {
  const ephemeral = srp.generateEphemeral(), salt = srp.generateSalt();
  const sessionId = randomBytes(16).toString('hex'), identity = `offerin-local:${sessionId}`;
  let session, challenge, context;
  return {
    code, offer: { type: 'offerin-offer', v: 2, sessionId, salt, A: ephemeral.public },
    accept(value) { challenge = value; context = `offerin-pair-v2|${sessionId}|${value.attemptId}|${value.pin}|${value.port}`; session = srp.deriveSession(ephemeral.secret, value.B, salt, identity, srp.derivePrivateKey(salt, identity, code)); },
    hello() { return { type: 'pair', sessionId, attemptId: challenge.attemptId, proof: session.proof, name: 'Native protocol peer' }; },
    confirm(msg) { srp.verifySession(ephemeral.public, session, msg.proof); if (!equal(msg.binding, binding(session.key, 'server', context))) throw new Error('Certificate binding rejected'); return { type: 'pair-confirm', binding: binding(session.key, 'client', context) }; }
  };
}
module.exports = { createPeer };
