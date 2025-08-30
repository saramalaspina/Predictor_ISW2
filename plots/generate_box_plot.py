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
ACUME_DATA_BASE = Path(__file__).parent.parent / "acumeFiles"


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
    weka_file_path = DATA_BASE / project_name / technique / "evaluationResults.csv"
    if not weka_file_path.exists():
        print(f"File Weka non trovato: {weka_file_path}")
        return
    df_weka = pd.read_csv(weka_file_path)

    print(f"--- Processando {project_name} - {technique} ---")

    key_cols = ['Classifier', 'FeatureSelection', 'Sampling', 'CostSensitive']
    for col in key_cols:
        df_weka[col] = df_weka[col].astype(str).fillna('None').replace(['none', '0', '0.0', 'nan'], 'None', regex=False)

    df_weka['Classifier'] = df_weka['Classifier'].replace({
        'weka.classifiers.bayes.NaiveBayes': 'NaiveBayes',
        'weka.classifiers.trees.RandomForest': 'RandomForest',
        'weka.classifiers.lazy.IBk': 'IBk'
    })

    df_weka.loc[df_weka['FeatureSelection'].str.contains('BestFirst', case=False, na=False), 'FeatureSelection'] = 'BestFirst'
    df_weka.loc[df_weka['Sampling'].str.contains('SMOTE', case=False, na=False), 'Sampling'] = 'SMOTE'
    df_weka.loc[df_weka['CostSensitive'].str.contains('Sensitive', case=False, na=False), 'CostSensitive'] = 'SensitiveLearning'

    for col in ['FeatureSelection', 'Sampling', 'CostSensitive']:
        valid_values = ['BestFirst', 'SMOTE', 'SensitiveLearning', 'None']
        df_weka.loc[~df_weka[col].isin(valid_values), col] = 'None'

    acume_file_path = ACUME_DATA_BASE / project_name / "output" / technique / "EAM_NEAM_output.csv"
    if not acume_file_path.exists():
        print(f"File Acume non trovato: {acume_file_path}. Salto l'aggiunta di Npofb20.")
        df_final = df_weka
    else:
        print(f"Trovato file Acume: {acume_file_path}")
        df_acume = pd.read_csv(acume_file_path)

        def parse_filename_robust(filename_str):
            fname_lower = filename_str.lower()

            if 'randomforest' in fname_lower: classifier = 'RandomForest'
            elif 'naivebayes' in fname_lower: classifier = 'NaiveBayes'
            elif 'ibk' in fname_lower: classifier = 'IBk'
            else: classifier = 'None'

            feature_selection = 'BestFirst' if '_fs' in fname_lower else 'None'
            sampling = 'SMOTE' if 'smote' in fname_lower else 'None'
            cost_sensitive = 'SensitiveLearning' if '_cs' in fname_lower else 'None'

            iteration_match = re.search(r'_(?:run|iter)(\d+)\.csv', fname_lower)
            iteration = int(iteration_match.group(1)) if iteration_match else -1

            return pd.Series([classifier, feature_selection, sampling, cost_sensitive, iteration])

        df_acume[['Classifier', 'FeatureSelection', 'Sampling', 'CostSensitive', 'Iteration']] = df_acume['Filename'].apply(parse_filename_robust)

        df_weka['Iteration'] = df_weka['Iteration'].astype(int)
        df_acume['Iteration'] = df_acume['Iteration'].astype(int)

        cols_to_merge = ['Classifier', 'FeatureSelection', 'Sampling', 'CostSensitive', 'Iteration', 'Npofb20']

        df_final = pd.merge(
            df_weka,
            df_acume[cols_to_merge],
            on=['Classifier', 'FeatureSelection', 'Sampling', 'CostSensitive', 'Iteration'],
            how='left'
        )

        matched_rows = df_final['Npofb20'].notna().sum()
        total_rows = len(df_final)
        print(f"Unione con dati Acume: {matched_rows}/{total_rows} righe abbinate con successo.")
        if matched_rows < total_rows:
            print("ATTENZIONE: Alcune righe non sono state abbinate.")

    df_final.fillna(0, inplace=True)
    df_final['Configuration'] = df_final.apply(create_label, axis=1)

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
    projects = ['openjpa', 'bookkeeper']
    techniques = ['crossValidation', 'walkForward']

    for project in projects:
        for technique in techniques:
            process_results(project, technique)


if __name__ == '__main__':
    main()