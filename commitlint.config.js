module.exports = {
	rules: {
		'type-enum': [2, 'always', [
			'feat', 'fix', 'docs', 'refactor', 'perf', 'test', 'build', 'ci', 'chore', 'revert'
		]],
		'type-case': [2, 'always', 'lower-case'],
		'subject-empty': [2, 'never'],
		'subject-max-length': [2, 'always', 100],
		'body-max-line-length': [0],
		'footer-max-line-length': [0]
	}
};