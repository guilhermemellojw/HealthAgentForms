import pandas as pd
import sys
import os

def load_and_filter(csv_path, email, entity):
    df = pd.read_csv(csv_path)
    # Normaliza colunas possíveis
    if 'agentName' in df.columns:
        df = df[df['agentName'].astype(str).str.lower() == email.lower()]
    elif 'email' in df.columns:
        df = df[df['email'].astype(str).str.lower() == email.lower()]
    else:
        # Para houses pode não ter agentName, mas pode ter 'agentUid' que usamos para filtrar se fornecido
        pass
    return df

def main():
    if len(sys.argv) != 2:
        print('Uso: python compare_datasets.py <agent_email>')
        sys.exit(1)
    email = sys.argv[1]
    # Paths (assumindo estrutura padrão criada pelos scripts)
    base_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
    local_houses = os.path.join(base_dir, 'local_export', 'local_houses.csv')
    local_activities = os.path.join(base_dir, 'local_export', 'local_activities.csv')
    cloud_houses = os.path.join(base_dir, 'firestore_export', 'cloud_houses.csv')
    cloud_activities = os.path.join(base_dir, 'firestore_export', 'cloud_activities.csv')

    # Carrega e filtra
    lh = load_and_filter(local_houses, email, 'house')
    la = load_and_filter(local_activities, email, 'activity')
    ch = load_and_filter(cloud_houses, email, 'house')
    ca = load_and_filter(cloud_activities, email, 'activity')

    # Identidade chave para houses (assumindo coluna identityKey ou construindo)
    if 'identityKey' not in lh.columns and set(['blockNumber','streetName','number','sequence','complement']).issubset(lh.columns):
        lh['identityKey'] = (lh['blockNumber'].astype(str) + '|' + lh['streetName'].astype(str) + '|' + lh['number'].astype(str) + '|' + lh['sequence'].astype(str) + '|' + lh['complement'].astype(str))
    if 'identityKey' not in ch.columns and set(['blockNumber','streetName','number','sequence','complement']).issubset(ch.columns):
        ch['identityKey'] = (ch['blockNumber'].astype(str) + '|' + ch['streetName'].astype(str) + '|' + ch['number'].astype(str) + '|' + ch['sequence'].astype(str) + '|' + ch['complement'].astype(str))

    # Comparações
    missing_in_local = ch[~ch['identityKey'].isin(lh['identityKey'])]
    missing_in_cloud = lh[~lh['identityKey'].isin(ch['identityKey'])]

    missing_in_local.to_csv('missing_in_local_houses.csv', index=False)
    missing_in_cloud.to_csv('missing_in_cloud_houses.csv', index=False)

    # Atividades – usar combinação date+agentName como chave
    if 'date' in la.columns:
        la['activityKey'] = la['date'].astype(str) + '|' + la['agentName'].astype(str)
    if 'date' in ca.columns:
        ca['activityKey'] = ca['date'].astype(str) + '|' + ca['agentName'].astype(str)

    missing_act_local = ca[~ca['activityKey'].isin(la['activityKey'])]
    missing_act_cloud = la[~la['activityKey'].isin(ca['activityKey'])]

    missing_act_local.to_csv('missing_in_local_activities.csv', index=False)
    missing_act_cloud.to_csv('missing_in_cloud_activities.csv', index=False)

    # Validação de colunas NOT NULL (ex.: sequence e complement não devem ser nulos)
    invalid_local = lh[(lh['sequence'].isnull()) | (lh['complement'].isnull())]
    invalid_local.to_csv('invalid_local_houses.csv', index=False)
    print('Comparação concluída. Arquivos de diff gerados na pasta scripts/')

if __name__ == '__main__':
    main()
