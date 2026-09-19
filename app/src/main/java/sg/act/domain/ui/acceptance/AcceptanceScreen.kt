package sg.act.domain.ui.acceptance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import sg.act.domain.R

/**
 * First-launch gate: the user must accept the Terms of Service and Privacy Policy
 * before reaching the app. Accepting also enables the optional cloud features
 * (revocable later in Settings). All copy comes from resources.
 *
 * This screen used to open with roughly 440 words, every one of them expanded:
 * an intro paragraph, the full Terms, the full Privacy Policy, and a consent
 * note. It was the first thing anyone saw, and nobody read it.
 *
 * It now leads with the three promises that actually distinguish this app, as
 * single lines. **Both legal documents are still here in full** — collapsed, with
 * an explicit "by continuing you accept both documents below" above them, which
 * is the standard pattern and strictly more likely to be read than a wall was.
 * Accepting still takes a deliberate tap on a labelled button.
 */
@Composable
fun AcceptanceScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(dimensionResource(R.dimen.space_l)),
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_m)),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_m)),
            ) {
                Text(
                    stringResource(R.string.accept_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.accept_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s))) {
                    Promise(stringResource(R.string.accept_point_local))
                    Promise(stringResource(R.string.accept_point_cloud))
                    Promise(stringResource(R.string.accept_point_storage))
                }

                Text(
                    stringResource(R.string.accept_legal_prompt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = dimensionResource(R.dimen.space_s)),
                )
                LegalDocument(
                    heading = stringResource(R.string.accept_tos_heading),
                    body = stringResource(R.string.accept_tos_body),
                )
                LegalDocument(
                    heading = stringResource(R.string.accept_privacy_heading),
                    body = stringResource(R.string.accept_privacy_body),
                )

                Text(
                    stringResource(R.string.accept_consent_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
            ) {
                OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.accept_action_decline))
                }
                Button(onClick = onAccept, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.accept_action_accept))
                }
            }
        }
    }
}

/**
 * One of the app's guarantees. The leading dot is the brand's "answered locally"
 * green — the same colour the chat screen badges an on-device reply with, so the
 * association starts on the first screen.
 */
@Composable
private fun Promise(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "●",
            style = MaterialTheme.typography.bodySmall,
            color = colorResource(R.color.brand_local),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A legal document, collapsed. Present and complete either way — the toggle only
 * decides whether it is on screen right now.
 */
@Composable
private fun LegalDocument(heading: String, body: String) {
    var open by rememberSaveable(heading) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape = RoundedCornerShape(dimensionResource(R.dimen.group_corner)))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { open = !open }
                .padding(
                    horizontal = dimensionResource(R.dimen.space_l),
                    vertical = dimensionResource(R.dimen.space_m),
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
        ) {
            Text(
                heading,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.accept_read),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = open) {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = dimensionResource(R.dimen.space_l),
                    end = dimensionResource(R.dimen.space_l),
                    bottom = dimensionResource(R.dimen.space_l),
                ),
            )
        }
    }
}
