package sg.act.domain.ui.acceptance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import sg.act.domain.R

/**
 * First-launch gate: the user must accept the Terms of Service and Privacy Policy
 * before reaching the app. Accepting also enables the optional cloud features
 * (revocable later in Settings). All copy comes from resources.
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

                Section(
                    heading = stringResource(R.string.accept_tos_heading),
                    body = stringResource(R.string.accept_tos_body),
                )
                Section(
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

@Composable
private fun Section(heading: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_xs))) {
        Text(
            heading,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(body, style = MaterialTheme.typography.bodySmall)
    }
}
