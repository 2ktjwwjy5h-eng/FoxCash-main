import { describe, expect, it } from 'vitest';
import {
  interpretAction,
  interpretButton,
  renderMessage,
  renderTemplate,
} from '../src/payload';
import type { DeliveryAction, StoreButton } from '../src/types';

function button(partial: Partial<StoreButton>): StoreButton {
  return { id: 'b1', label: 'x', action: 'BUY', payload: null, position: 0, ...partial };
}

function action(partial: Partial<DeliveryAction>): DeliveryAction {
  return {
    seq: 0,
    actionType: 'SEND_MESSAGE',
    payload: {},
    status: 'PENDING',
    note: null,
    executedAt: null,
    ...partial,
  };
}

describe('renderTemplate (Spec 51)', () => {
  it('substitui {nome} e {plano}', () => {
    expect(renderTemplate('Olá {nome}, seu {plano} está pronto', { nome: 'Ana', plano: 'VIP' })).toBe(
      'Olá Ana, seu VIP está pronto',
    );
  });

  it('variável ausente → string vazia', () => {
    expect(renderTemplate('Olá {nome}', {})).toBe('Olá ');
  });

  it('renderMessage devolve undefined quando a loja não definiu a etapa', () => {
    expect(renderMessage({}, 'welcome', {})).toBeUndefined();
  });
});

describe('interpretButton (Spec 50)', () => {
  it('BUY/CLOSE não têm payload', () => {
    expect(interpretButton(button({ action: 'BUY' }))).toEqual({ action: 'BUY', payload: null });
    expect(interpretButton(button({ action: 'CLOSE' }))).toEqual({
      action: 'CLOSE',
      payload: null,
    });
  });

  it('PREVIEW exige mediaUrls', () => {
    const view = interpretButton(
      button({ action: 'PREVIEW', payload: { mediaUrls: ['https://cdn/x.png'] } }),
    );
    expect(view).toEqual({ action: 'PREVIEW', mediaUrls: ['https://cdn/x.png'] });
    expect(interpretButton(button({ action: 'PREVIEW', payload: {} }))).toBeNull();
  });

  it('FAQ exige text', () => {
    const view = interpretButton(button({ action: 'FAQ', payload: { text: 'Pergunta' } }));
    expect(view).toEqual({ action: 'FAQ', text: 'Pergunta' });
    expect(interpretButton(button({ action: 'FAQ', payload: {} }))).toBeNull();
  });

  it('SOCIAL/LINK exige url, label opcional', () => {
    const view = interpretButton(
      button({ action: 'LINK', payload: { url: 'https://x.com', label: 'Site' } }),
    );
    expect(view).toEqual({ action: 'LINK', url: 'https://x.com', label: 'Site' });
    expect(interpretButton(button({ action: 'SOCIAL', payload: {} }))).toBeNull();
  });
});

describe('interpretAction (Spec 49)', () => {
  it('GRANT_ROLE exige roleId', () => {
    const view = interpretAction(action({ actionType: 'GRANT_ROLE', payload: { roleId: 'r1' } }));
    expect(view).toEqual({ actionType: 'GRANT_ROLE', roleId: 'r1' });
    expect(interpretAction(action({ actionType: 'GRANT_ROLE', payload: {} }))).toBeNull();
  });

  it('SEND_MESSAGE exige text', () => {
    const view = interpretAction(action({ actionType: 'SEND_MESSAGE', payload: { text: 'oi' } }));
    expect(view).toEqual({ actionType: 'SEND_MESSAGE', text: 'oi' });
  });

  it('SEND_LINK exige url, label opcional', () => {
    const view = interpretAction(
      action({ actionType: 'SEND_LINK', payload: { url: 'https://t.me/+abc' } }),
    );
    expect(view).toEqual({ actionType: 'SEND_LINK', url: 'https://t.me/+abc' });
  });

  it('ADD_TO_GROUP exige groupId', () => {
    const view = interpretAction(
      action({ actionType: 'ADD_TO_GROUP', payload: { groupId: 'https://t.me/+g' } }),
    );
    expect(view).toEqual({ actionType: 'ADD_TO_GROUP', groupId: 'https://t.me/+g' });
  });

  it('SEND_FILE exige fileUrl', () => {
    const view = interpretAction(
      action({ actionType: 'SEND_FILE', payload: { fileUrl: 'https://cdn/x.zip' } }),
    );
    expect(view).toEqual({ actionType: 'SEND_FILE', fileUrl: 'https://cdn/x.zip' });
    expect(interpretAction(action({ actionType: 'SEND_FILE', payload: {} }))).toBeNull();
  });
});
