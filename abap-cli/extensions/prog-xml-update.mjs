/**
 * abap-cli command extension: `prog-xml-update`
 *
 * Thin wrapper around `prog-xml-create` that hard-codes `updateOnly = true`,
 * so the action PUTs the title onto existing objects instead of POSTing new
 * ones. Use it after a successful create run that picked up the wrong title,
 * or whenever abapGit's PROGDIR I18N_TPOOL has changed but the object
 * already exists in SAP.
 *
 * Registered as a separate command (rather than a `--update` flag on
 * `prog-xml-create`) because the abap-cli extension host registers
 * commands without an `.option(...)` call, so commander rejects any
 * unknown flag before our action runs.
 *
 *   abap prog-xml-update repos/<pkg>/src/*.prog.xml
 */

import { updateExtension } from './prog-xml-create.mjs';

export default updateExtension;