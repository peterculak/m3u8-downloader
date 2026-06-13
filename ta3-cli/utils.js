'use strict';

/**
 * Sanitizes a title string to be safe for filenames.
 * - Normalizes accents and removes diacritics (e.g. á -> a, š -> s).
 * - Strips any characters that are not alphanumeric, spaces, dashes, or underscores.
 * - Replaces any sequence of spaces or underscores with a single dash.
 * - Strips leading/trailing dots, dashes, and underscores (to prevent hidden files and clean filenames).
 *
 * @param {string} title
 * @returns {string}
 */
function sanitizeTitle(title) {
  return title
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-zA-Z0-9\s-_]/g, '')
    .trim()
    .replace(/[\s_]+/g, '-')
    .replace(/^[-_.]+|[-_.]+$/g, '');
}

module.exports = {
  sanitizeTitle
};
