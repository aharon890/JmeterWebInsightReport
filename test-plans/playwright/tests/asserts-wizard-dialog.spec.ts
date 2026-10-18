import { test, expect, Page } from '@playwright/test';
import { loadClean, switchTab, expectNoJsErrors } from './helpers';

/**
 * B3: the Generate from run… wizard keeps the dialog contract the other four dialogs keep (U4).
 *
 * It was never given wireDialog. Opened by the keyboard, focus stayed on the button behind the
 * scrim; Tab walked the toolbar and the rules table under the dimmed backdrop (the scrim stops
 * only the mouse), and Shift+Tab from its × landed on a rule's Delete — Enter on it deleted the
 * rule with the wizard still open over it. Escape did nothing, Cancel and × dropped focus to
 * <body>, the panel was not aria-modal, and a shape switch rebuilt the whole modal, so focus fell
 * to <body> there too. Apply left focus wherever the rebuilt table put it — a Delete, again.
 *
 * Both openers: the toolbar's Generate from run… (E25) and the gateless empty state's Generate
 * from this run… (E02). These drive the real keyboard.
 */

const PANEL = '#assertWizard .wir-modal';
const CLOSE = `${PANEL} .wir-slideover-head button[aria-label="Close"]`;

/** True while the focused element is inside the wizard's panel. */
function focusInWizard(page: Page) {
  return page.evaluate((sel) => !!document.activeElement?.closest(sel), PANEL);
}

/** The focused element, said the way a failure message can be read. */
function focused(page: Page) {
  return page.evaluate(() => {
    const a = document.activeElement as HTMLElement | null;
    if (!a || a === document.body) return '<body>';
    return a.nodeName.toLowerCase() + (a.id ? '#' + a.id : '') + ' "' + (a.textContent || '').trim().slice(0, 30) + '"';
  });
}

async function openWith(page: Page, fixture: string, opener: string) {
  await loadClean(page, fixture);
  await switchTab(page, 'asserts');
  await page.focus(opener);
  await page.keyboard.press('Enter');
  await expect(page.locator(PANEL)).toBeVisible();
}

/** Label the panel on screen, so a rebuilt one can be told from the one that was wired. */
async function markPanel(page: Page) {
  await page.locator(PANEL).evaluate((el) => { (el as any).wirOpening = 'wired'; });
}

async function expectSamePanel(page: Page, why: string) {
  expect(await page.locator(PANEL).evaluate((el) => (el as any).wirOpening === 'wired'),
    `${why}: the panel was rebuilt, so its wiring went with it`).toBe(true);
}

/** The U4 contract: modal, named by its heading, focus inside, Tab and Shift+Tab kept inside. */
async function expectModal(page: Page, why: string, tabs = 40) {
  const dialog = page.locator(PANEL);
  await expect(dialog, why).toHaveAttribute('aria-modal', 'true');
  await expect(dialog, why).toHaveAttribute('aria-labelledby', 'assertWizardTitle');
  await expect(page.locator('#assertWizardTitle'), why).toHaveText('Generate from this run');
  expect(await focusInWizard(page), `${why}: focus is on ${await focused(page)}, not in the wizard`).toBe(true);
  for (let i = 0; i < tabs; i++) {
    await page.keyboard.press(i % 7 === 6 ? 'Shift+Tab' : 'Tab');
    expect(await focusInWizard(page), `${why}: Tab ${i + 1} walked out of the wizard to ${await focused(page)}`)
      .toBe(true);
  }
}

/** Escape closes it, and focus goes back to `opener`. */
async function expectEscapeReturnsTo(page: Page, why: string, opener: string) {
  await page.keyboard.press('Escape');
  await expect(page.locator(PANEL), `${why}: Escape left the wizard open`).toHaveCount(0);
  await expect(page.locator(opener), `${why}: focus is on ${await focused(page)}`).toBeFocused();
}

test.describe('B3: the Generate wizard is a dialog to the keyboard [E25]', () => {
  test('[U] opened from Generate from run…: focus moves in, Tab stays in, Escape closes onto the opener', async ({ page }) => {
    await expectNoJsErrors(page, async () => {
      await openWith(page, 'E25', '#generateAssertsBtn');
      // The first control of the body, as wireDialog puts it on every opening.
      await expect(page.locator('#wizardShapePerSampler')).toBeFocused();
      await expectModal(page, 'opened');
      await expectEscapeReturnsTo(page, 'opened', '#generateAssertsBtn');
    });
  });

  test('[U] Tab from the last control wraps to the first, Shift+Tab from the first to the last', async ({ page }) => {
    await openWith(page, 'E25', '#generateAssertsBtn');
    await expect(page.locator('#wizardApply')).toBeEnabled();
    await page.focus('#wizardApply');
    await page.keyboard.press('Tab');
    await expect(page.locator(CLOSE), `Tab from Apply went to ${await focused(page)}`).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(page.locator('#wizardApply'), `Shift+Tab from × went to ${await focused(page)}`).toBeFocused();
  });

  test('[U] a shape switch keeps the panel, aria-modal, the trap and the focus on the shape pressed', async ({ page }) => {
    await openWith(page, 'E25', '#generateAssertsBtn');
    await markPanel(page);

    await page.focus('#wizardShapeBlanket');
    await page.keyboard.press('Enter');
    await expect(page.locator('#wizardShapeBlanket')).toHaveClass(/wir-seg-active/);
    await expect(page.locator('#wizardShapeBlanket'), `focus fell to ${await focused(page)}`).toBeFocused();
    await expectSamePanel(page, 'after Blanket');
    await expectModal(page, 'after Blanket');

    await page.focus('#wizardShapePerSampler');
    await page.keyboard.press('Enter');
    await expect(page.locator('#wizardShapePerSampler')).toHaveClass(/wir-seg-active/);
    await expect(page.locator('#wizardShapePerSampler')).toBeFocused();
    await expectSamePanel(page, 'after Per sampler');
    await expectEscapeReturnsTo(page, 'after two shape switches', '#generateAssertsBtn');
  });

  test('[U] a ticked box and a typed tolerance keep the panel and their focus', async ({ page }) => {
    await openWith(page, 'E25', '#generateAssertsBtn');
    await markPanel(page);

    const box = page.locator('#wizardSamplerTable tbody input[type=checkbox]').first();
    await box.focus();
    await page.keyboard.press('Space');
    await expect(box).not.toBeChecked();
    await expect(box).toBeFocused();

    const input = page.locator('#wizardHeadroomInput');
    await input.focus();
    await input.press('Control+a');
    await input.type('35', { delay: 20 });
    await expect(input).toBeFocused();
    await expect(input).toHaveValue('35');

    // A shape switch is a redraw of the body: the box stays unticked and the tolerance typed.
    await page.focus('#wizardShapeBlanket');
    await page.keyboard.press('Enter');
    await expect(box).not.toBeChecked();
    await expect(input).toHaveValue('35');
    await expectSamePanel(page, 'after a tick, a typed tolerance and a shape switch');
    await expectModal(page, 'after a tick, a typed tolerance and a shape switch', 10);
  });

  test('[U] Cancel and × by the keyboard close onto the opener, never <body>', async ({ page }) => {
    await openWith(page, 'E25', '#generateAssertsBtn');
    await page.locator(PANEL).getByRole('button', { name: 'Cancel' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.locator(PANEL)).toHaveCount(0);
    await expect(page.locator('#generateAssertsBtn'), `Cancel left focus on ${await focused(page)}`).toBeFocused();

    await page.keyboard.press('Enter');
    await expect(page.locator(PANEL)).toBeVisible();
    await page.focus(CLOSE);
    await page.keyboard.press('Enter');
    await expect(page.locator(PANEL)).toHaveCount(0);
    await expect(page.locator('#generateAssertsBtn'), `× left focus on ${await focused(page)}`).toBeFocused();
  });

  test('[U] Apply closes onto Generate from run…, not onto a rule behind it', async ({ page }) => {
    await openWith(page, 'E25', '#generateAssertsBtn');
    const before = await page.locator('tr.wir-rule-row').count();
    await page.focus('#wizardApply');
    await page.keyboard.press('Enter');
    await expect(page.locator(PANEL)).toHaveCount(0);
    expect(await page.locator('tr.wir-rule-row').count()).toBeGreaterThan(before);
    await expect(page.locator('#generateAssertsBtn'), `Apply left focus on ${await focused(page)}`).toBeFocused();
  });
});

test.describe('B3: the Generate wizard from a gateless run\'s empty state [E02]', () => {
  test('[U] opened from Generate from this run…: modal, Escape closes onto the empty state\'s button', async ({ page }) => {
    await openWith(page, 'E02', '#emptyGenerateBtn');
    await expectModal(page, 'opened from the empty state');
    await expectEscapeReturnsTo(page, 'opened from the empty state', '#emptyGenerateBtn');
  });

  test('[U] Apply leaves no empty state behind: focus goes to the toolbar\'s Generate from run…', async ({ page }) => {
    await openWith(page, 'E02', '#emptyGenerateBtn');
    await page.focus('#wizardApply');
    await page.keyboard.press('Enter');
    await expect(page.locator(PANEL)).toHaveCount(0);
    await expect(page.locator('#assertsEmpty')).toHaveCount(0);
    await expect(page.locator('#generateAssertsBtn'), `Apply left focus on ${await focused(page)}`).toBeFocused();
  });
});
