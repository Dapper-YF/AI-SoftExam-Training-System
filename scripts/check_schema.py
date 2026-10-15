import pymysql, os
conn = pymysql.connect(host='127.0.0.1', port=3306, user='root', password=os.environ.get('MYSQL_PASSWORD','123123'), database='ai_exam_platform', charset='utf8mb4')
cur = conn.cursor()
for table in ['exam_qualification', 'exam_subject', 'exam_rule']:
    cur.execute(f"SELECT column_name, is_nullable FROM information_schema.columns WHERE table_schema='ai_exam_platform' AND table_name='{table}' ORDER BY ordinal_position")
    print(f'{table} columns:')
    for row in cur.fetchall():
        print(f'  {row[0]} (nullable={row[1]})')
    print()
conn.close()
