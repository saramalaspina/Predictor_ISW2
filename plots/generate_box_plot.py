import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
from pathlib import Path
import numpy as np
import re

# Cartella di output dei grafici (nella cartella corrente dello script)
OUTPUT_BASE = Path(__file__).parent / "results"

# Cartella dei dati (wekaResults è fuori dalla cartella dello script)
DATA_BASE = Path(__file__).parent.parent / "wekaResults"

# Cartella dei dati di Acume
ACUME_DATA_BASE = Path(__file__).parent.parent / "acumeResults"


def create_box_plot(df, metric, title, output_path):
    """
    Crea e salva un box plot per una data metrica.
    """
    if metric not in df.columns or df[metric].isnull().all() or (df[metric] == 0).all():
        print(f"Metrica '{metric}' non trovata o contenente solo zeri. Grafico non creato.")
        return

    plt.figure(figsize=(16, 9))
    order = df.groupby('Configuration')[metric].median().sort_values(ascending=False).index

    sns.boxplot(
        data=df,
        x='Configuration',
        y=metric,
        hue='Configuration',
        order=order,
        palette='viridis',
        whis=[0, 100],
        legend=False
    )

    plt.title(title, fontsize=20, pad=20)
    plt.ylabel(metric, fontsize=14)
    plt.xlabel('Classifier Configuration', fontsize=14)
    plt.xticks(rotation=45, ha="right")
    plt.grid(axis='y', linestyle='--', alpha=0.7)
    plt.tight_layout()

    output_path.mkdir(parents=True, exist_ok=True)
    output_filename = output_path / f'boxplot_{metric.lower().replace("-", "_")}.png'
    plt.savefig(output_filename, dpi=300)
    print(f"Grafico salvato come: {output_filename}")
    plt.close()


def create_label(row):
    """
    Crea un'etichetta di configurazione pulita per una riga del DataFrame.
    """
    parts = [row['Classifier']]
    if row['FeatureSelection'] not in ['none', 'None', '0', 0]:
        parts.append(row['FeatureSelection'])
    if row['Sampling'] not in ['none', 'None', '0', 0]:
        parts.append(row['Sampling'])
    if row['CostSensitive'] not in ['none', 'None', '0', 0]:
        parts.append(row['CostSensitive'])
    return ' + '.join(parts)


def process_results(project_name, technique):
    """
    Carica i risultati da weka e acume e li unisce per creare i plot.
    """
    # 1. Carica il file CSV principale da wekaResults
    weka_file_path = DATA_BASE / project_name / technique / "evaluationResults.csv"
    if not weka_file_path.exists():
        print(f""
              f"File Weka non trovato: {weka_file_path}")
        return
    df_weka = pd.read_csv(weka_file_path)

    print(f"--- Processando {project_name} - {technique} ---")

    # ---- STANDARDIZZAZIONE DEI VALORI "None" ----
    # Questo è il passo cruciale per unire correttamente i dati.
    key_cols = ['FeatureSelection', 'Sampling', 'CostSensitive']
    for col in key_cols:
        # Sostituisce i NaN e le varie forme di "nessuno" ('none', 0) con la stringa 'None'
        df_weka[col] = df_weka[col].fillna('None').replace(['none', 0, '0'], 'None')
    # ---------------------------------------------

    # 2. Carica il file CSV di Acume
    acume_file_path = ACUME_DATA_BASE / project_name / technique / "EAM_NEAM_output.csv"
    if not acume_file_path.exists():
        print(f"File Acume non trovato: {acume_file_path}. Salto l'aggiunta di Npofb20.")
        df_final = df_weka
    else:
        print(f"Trovato file Acume: {acume_file_path}")
        df_acume = pd.read_csv(acume_file_path)

        # 3. Prepara df_acume per l'unione: estrai le informazioni dal 'Filename'
        def parse_filename(filename_str):
            classifier_match = re.search(r'_(randomforest|naivebayes|ibk)', filename_str.lower())
            classifier = classifier_match.group(1) if classifier_match else 'None'

            if classifier == 'ibk': classifier = 'IBk'
            if classifier == 'randomforest': classifier = 'RandomForest'
            if classifier == 'naivebayes': classifier = 'NaiveBayes'

            feature_selection = 'BestFirst' if '_fs' in filename_str else 'None'
            sampling = 'SMOTE' if '_smote' in filename_str else 'None'
            cost_sensitive = 'SensitiveLearning' if '_cs' in filename_str else 'None'

            iteration_match = re.search(r'_(run|iter)(\d+)\.csv', filename_str)
            iteration = int(iteration_match.group(2)) if iteration_match else -1
            return pd.Series([classifier, feature_selection, sampling, cost_sensitive, iteration])

        df_acume[['Classifier', 'FeatureSelection', 'Sampling', 'CostSensitive', 'Iteration']] = df_acume['Filename'].apply(parse_filename)

        # 4. Unisci i due DataFrame
        df_weka['Iteration'] = df_weka['Iteration'].astype(int)
        df_acume['Iteration'] = df_acume['Iteration'].astype(int)

        cols_to_merge = ['Classifier', 'FeatureSelection', 'Sampling', 'CostSensitive', 'Iteration', 'Npofb20']

        df_final = pd.merge(
            df_weka,
            df_acume[cols_to_merge],
            on=['Classifier', 'FeatureSelection', 'Sampling', 'CostSensitive', 'Iteration'],
            how='left'
        )

        if 'Npofb20' in df_final.columns and df_final['Npofb20'].notna().any():
            print(f"Unione con dati Acume riuscita. {df_final['Npofb20'].notna().sum()} righe abbinate.")
        else:
            print("ATTENZIONE: Nessuna riga è stata abbinata con i dati Acume.")

    # 5. Pulizia finale e creazione label
    df_final.fillna(0, inplace=True)
    df_final['Configuration'] = df_final.apply(create_label, axis=1)

    # 6. Crea i grafici
    output_path = OUTPUT_BASE / project_name / technique
    metrics = ['AUC', 'Recall', 'F1-Score', 'Precision', 'Kappa', 'Npofb20']

    for metric in metrics:
        if metric in df_final.columns:
            create_box_plot(
                df_final,
                metric,
                f"Distribuzione di {metric} per Configurazione ({project_name} - {technique})",
                output_path
            )
        else:
            print(f"Metrica '{metric}' non trovata nel DataFrame finale. Salto il grafico.")


def main():
    projects = ['bookkeeper']
    techniques = ['crossValidation', 'walkForward']

    for project in projects:
        for technique in techniques:
            process_results(project, technique)


if __name__ == '__main__':
    main()